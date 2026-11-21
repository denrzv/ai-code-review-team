package dev.denrzv.codereview.adapter.llm;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import static dev.denrzv.codereview.adapter.llm.DeepSeekProtocolSmoke.FIXTURE_ID;
import static dev.denrzv.codereview.adapter.llm.DeepSeekProtocolSmoke.MAX_OUTPUT_PER_REQUEST;
import static dev.denrzv.codereview.adapter.llm.DeepSeekProtocolSmoke.Outcome;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeepSeekProtocolTest {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String COMPLETIONS_PATH = "/chat/completions";
    private static final String KEY = "synthetic-test-key";
    private static final String MODEL = "configured-smoke-model";
    private static final String DEFAULT_MODEL = "deepseek-flash";
    private static final String PROVIDER_PRIVATE_TEXT = "provider-text-never-returned";
    private static final String STOP = "stop";
    private static final String FIRST_ID = "call_fixture_1";
    private static final String SECOND_ID = "call_fixture_2";
    private static final String READ_TOOL = "read_fixture";
    private static final String FIRST_ARGS = "{\"part\":\"first\"}";
    private static final String SECOND_ARGS = "{\"part\":\"second\"}";
    private static final String VALID_PLAN = """
            {"action":"COMPLETE","agent":null,"task":null,"rationale":"synthetic fixture","topicKey":null}
            """;
    private static final LocalDate PRICES_AS_OF = LocalDate.of(2026, 11, 21);
    private static final DeepSeekProtocolSmoke.Price PRICE = new DeepSeekProtocolSmoke.Price(
            PRICES_AS_OF, new BigDecimal("0.006"), new BigDecimal("0.30"), new BigDecimal("1.20"));
    private static final Duration ELAPSED = Duration.ofSeconds(10);
    private static final BigDecimal USD = new BigDecimal("0.02");
    private static final DeepSeekProtocolSmoke.Budget BUDGET =
            new DeepSeekProtocolSmoke.Budget(5, 2, 10_000, 2_000, USD, ELAPSED);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void sendsConfiguredWireFieldsAndCarriesTwoOrderedToolResultsInHistory() throws IOException {
        try (var stub = new Stub(successfulTurn())) {
            var result = run(stub, BUDGET);

            assertThat(result.outcome()).isEqualTo(Outcome.COMPLETE);
            assertThat(result.decision().action()).isEqualTo(DeepSeekProtocolSmoke.PlanAction.COMPLETE);
            assertThat(result.toolCalls()).isEqualTo(2);
            assertThat(result.attempts()).hasSize(4);
            assertThat(result.pricesAsOf()).isEqualTo(PRICES_AS_OF);
            var requests = stub.requests;
            assertThat(requests).hasSize(4).allSatisfy(request -> {
                assertThat(request.path("model").asText()).isEqualTo(MODEL);
                assertThat(request.at("/thinking/type").asText()).isEqualTo("disabled");
                assertThat(request.path("max_tokens").asInt()).isEqualTo(MAX_OUTPUT_PER_REQUEST);
                assertThat(request.has("server_tools")).isFalse();
                assertThat(request.has("max_completion_tokens")).isFalse();
            });
            var planner = requests.getFirst();
            assertThat(planner.at("/response_format/type").asText()).isEqualTo("json_object");
            assertThat(planner.has("tools")).isFalse();
            assertThat(planner.at("/messages/0/content").asText()).contains(
                    "{\"action\":\"COMPLETE\",\"agent\":null,\"task\":null,\"rationale\":\"Fixture ready\",\"topicKey\":null}");
            assertThat(stub.requestLines).hasSize(4).containsOnly("POST " + COMPLETIONS_PATH);

            assertSingleToolSpecification(requests.subList(1, 4));
            assertToolHistory(requests.get(2), 1, FIRST_ID, "int apples = 2;");
            assertToolHistory(requests.get(3), 1, FIRST_ID, "int apples = 2;");
            assertToolHistory(requests.get(3), 3, SECOND_ID, "int pears = 3;");
            assertThat(result.attempts().subList(1, 4)).extracting(DeepSeekProtocolSmoke.Attempt::reservedInputTokens)
                    .isSortedAccordingTo(Integer::compareTo).doesNotHaveDuplicates();
        }
    }

    private static void assertSingleToolSpecification(List<JsonNode> requests) {
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.path("tools").size()).isEqualTo(1);
            assertThat(request.at("/tools/0/type").asText()).isEqualTo("function");
            assertThat(request.at("/tools/0/function/name").asText()).isEqualTo(READ_TOOL);
            assertThat(request.at("/tools/0/function/parameters/properties/part/enum"))
                    .isEqualTo(JSON.createArrayNode().add("first").add("second"));
        });
    }

    private static void assertToolHistory(JsonNode request, int assistantIndex, String id, String content) {
        var assistant = request.path("messages").path(assistantIndex);
        var toolResult = request.path("messages").path(assistantIndex + 1);
        assertThat(assistant.path("role").asText()).isEqualTo("assistant");
        assertThat(assistant.at("/tool_calls/0/id").asText()).isEqualTo(id);
        assertThat(toolResult.path("role").asText()).isEqualTo("tool");
        assertThat(toolResult.path("tool_call_id").asText()).isEqualTo(id);
        assertThat(toolResult.path("content").asText()).isEqualTo(content);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPlannerOutput")
    void repairsInvalidPlannerOutputOnce(String scenario, String badReply) throws IOException {
        var replies = new ArrayList<Reply>();
        replies.add(new Reply(200, badReply));
        replies.addAll(successfulTurn());
        try (var stub = new Stub(replies)) {
            var result = run(stub, BUDGET);
            assertThat(result.outcome()).as(scenario).isEqualTo(Outcome.COMPLETE);
            assertThat(result.attempts()).hasSize(5);
            assertThat(stub.requests.get(1).path("messages").size()).isEqualTo(2);
            assertThat(stub.requests.get(1).at("/response_format/type").asText()).isEqualTo("json_object");
            assertThat(stub.requests.get(1).toString()).doesNotContain(PROVIDER_PRIVATE_TEXT);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPlannerOutput")
    void returnsTypedInvalidWhenRepairAlsoFails(String scenario, String badReply) throws IOException {
        try (var stub = new Stub(List.of(new Reply(200, badReply), new Reply(200, badReply)))) {
            var result = run(stub, BUDGET);
            assertThat(result.outcome()).as(scenario).isEqualTo(Outcome.INVALID_RESPONSE);
            assertThat(result.decision()).isNull();
            assertThat(result.attempts()).hasSize(2);
            assertThat(stub.requests).hasSize(2);
        }
    }

    private static Stream<Arguments> invalidPlannerOutput() {
        return Stream.of(
                Arguments.of("blank", completion("   ", STOP, null)),
                Arguments.of("truncated", completion(VALID_PLAN, "length", null)),
                Arguments.of("malformed", completion(PROVIDER_PRIVATE_TEXT, STOP, null)),
                Arguments.of("schema-invalid", completion("{\"action\":\"INVENTED\"}", STOP, null)),
                Arguments.of("trailing data", completion(VALID_PLAN + "{}", STOP, null)));
    }

    @ParameterizedTest
    @CsvSource({"400,INVALID_REQUEST,false", "401,AUTHENTICATION,false", "403,POLICY_REFUSAL,false",
            "307,INVALID_REQUEST,false", "429,RATE_LIMIT,true", "500,TRANSIENT_PROVIDER,true",
            "503,TRANSIENT_PROVIDER,true"})
    void classifiesHttpErrorsWithoutAutomaticRetries(int status, Outcome expected, boolean retryable) throws IOException {
        try (var stub = new Stub(List.of(new Reply(status, PROVIDER_PRIVATE_TEXT)))) {
            var result = run(stub, BUDGET);
            assertThat(result.outcome()).isEqualTo(expected);
            assertThat(result.outcome().retryable()).isEqualTo(retryable);
            assertThat(result.attempts()).hasSize(1);
            assertThat(stub.requests).hasSize(1);
            assertThat(result.toString()).doesNotContain(KEY, PROVIDER_PRIVATE_TEXT);
        }
    }

    @Test
    void classifiesEarlyDisconnectWithoutReplayingThePost() throws IOException {
        try (var stub = new Stub(List.of(new Reply(0, null)))) {
            var result = run(stub, BUDGET);
            assertThat(result.outcome()).isEqualTo(Outcome.TRANSIENT_TRANSPORT);
            assertThat(result.outcome().retryable()).isTrue();
            assertThat(result.attempts()).hasSize(1);
            assertThat(stub.requests).hasSize(1);
        }
    }

    @Test
    void boundsElapsedTimeWhileWaitingForTheProvider() throws IOException {
        var budget = new DeepSeekProtocolSmoke.Budget(5, 2, 10_000, 2_000, USD, Duration.ofSeconds(1));
        try (var stub = new Stub(List.of(new Reply(-1, null)))) {
            var result = run(stub, budget);
            assertThat(result.outcome()).isEqualTo(Outcome.TRANSIENT_TRANSPORT);
            assertThat(result.elapsed()).isLessThan(Duration.ofSeconds(3));
            assertThat(stub.requests).hasSize(1);
        }
    }

    @Test
    void bypassesRepairOnPolicyRefusalAndKeepsAvailableUsage() throws IOException {
        try (var stub = new Stub(List.of(new Reply(200, completion(PROVIDER_PRIVATE_TEXT, "content_filter",
                "{\"prompt_tokens\":160,\"completion_tokens\":12}"))))) {
            var result = run(stub, BUDGET);
            assertThat(result.outcome()).isEqualTo(Outcome.POLICY_REFUSAL);
            assertThat(result.outcome().retryable()).isFalse();
            assertThat(result.toString()).doesNotContain(PROVIDER_PRIVATE_TEXT);
            assertThat(stub.requests).hasSize(1);
            var attempt = result.attempts().getFirst();
            assertThat(attempt.observed()).isEqualTo(new DeepSeekAdapter.Usage(160, 12, null, null, null, null, null));
            assertThat(attempt.estimatedUsd()).isEqualByComparingTo("0.0000624");
        }
    }

    @Test
    void bypassesRepairWhenTheAssistantExplicitlyRefuses() throws IOException {
        var message = JSON.createObjectNode().put("role", "assistant").putNull("content")
                .put("refusal", PROVIDER_PRIVATE_TEXT);
        try (var stub = new Stub(List.of(new Reply(200, envelope(message, STOP, null))))) {
            var result = run(stub, BUDGET);
            assertThat(result.outcome()).isEqualTo(Outcome.POLICY_REFUSAL);
            assertThat(result.toString()).doesNotContain(PROVIDER_PRIVATE_TEXT);
            assertThat(stub.requests).hasSize(1);
        }
    }

    @Test
    void keepsUsageUnknownAndEstimatesCostFromObservedFieldsOrReservation() throws IOException {
        try (var stub = new Stub(successfulTurn())) {
            var attempts = run(stub, BUDGET).attempts();
            var measured = attempts.getFirst();
            assertThat(measured.observed()).isEqualTo(new DeepSeekAdapter.Usage(100, 12, 40, 60, 112, 0, null));
            assertThat(measured.estimatedUsd()).isEqualByComparingTo("0.00003264");
            var unknown = attempts.get(1);
            assertThat(unknown.observed()).isEqualTo(DeepSeekAdapter.Usage.UNKNOWN);
            assertThat(unknown.estimatedUsd()).isEqualByComparingTo(unknown.reservedUsd());
        }
    }

    @Test
    void estimatesFromAvailableCacheCountsWhenPromptTotalIsAbsent() throws IOException {
        var usage = """
                {"completion_tokens":12,"prompt_tokens_details":{"cached_tokens":40},"prompt_cache_miss_tokens":60}
                """;
        try (var stub = new Stub(List.of(new Reply(200, completion("", "aborted", usage))))) {
            var result = run(stub, BUDGET);
            var attempt = result.attempts().getFirst();
            assertThat(result.outcome()).isEqualTo(Outcome.TRANSIENT_PROVIDER);
            assertThat(stub.requests).hasSize(1);
            assertThat(attempt.observed()).isEqualTo(new DeepSeekAdapter.Usage(null, 12, 40, 60, null, null, null));
            assertThat(attempt.estimatedUsd()).isEqualByComparingTo("0.00003264");
        }
    }

    @Test
    void stopsBeforeContinuingWhenProviderReportsExcessOutputTokens() throws IOException {
        var usage = "{\"completion_tokens\":" + (MAX_OUTPUT_PER_REQUEST + 1) + "}";
        try (var stub = new Stub(List.of(new Reply(200, completion(VALID_PLAN, STOP, usage))))) {
            var result = run(stub, BUDGET);
            assertThat(result.outcome()).isEqualTo(Outcome.BUDGET_EXHAUSTED);
            assertThat(result.attempts().getFirst().observed().output()).isEqualTo(MAX_OUTPUT_PER_REQUEST + 1);
            assertThat(stub.requests).hasSize(1);
        }
    }

    @ParameterizedTest
    @MethodSource("excessUsage")
    void stopsBeforeRepairWhenAnInvalidEnvelopeReportsExcessUsage(String usage) throws IOException {
        var incompleteEnvelope = "{\"choices\":[],\"usage\":" + usage + "}";
        try (var stub = new Stub(List.of(new Reply(200, incompleteEnvelope)))) {
            var result = run(stub, BUDGET);
            assertThat(result.outcome()).isEqualTo(Outcome.BUDGET_EXHAUSTED);
            assertThat(stub.requests).hasSize(1);
        }
    }

    private static Stream<String> excessUsage() {
        return Stream.of(
                "{\"completion_tokens\":300}",
                "{\"prompt_cache_hit_tokens\":7000}",
                "{\"prompt_cache_hit_tokens\":3500,\"prompt_cache_miss_tokens\":3500}",
                "{\"total_tokens\":7000}",
                "{\"prompt_tokens\":15,\"total_tokens\":300}");
    }

    @Test
    void reportsBudgetExhaustionDuringADispatchedRepair() throws IOException {
        var first = completion(PROVIDER_PRIVATE_TEXT, STOP, null);
        var repair = completion(VALID_PLAN, STOP, "{\"completion_tokens\":300}");
        try (var stub = new Stub(List.of(new Reply(200, first), new Reply(200, repair)))) {
            var result = run(stub, BUDGET);
            assertThat(result.outcome()).isEqualTo(Outcome.BUDGET_EXHAUSTED);
            assertThat(result.attempts()).hasSize(2);
            assertThat(stub.requests).hasSize(2);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("insufficientRepairBudgets")
    void retainsInvalidPlannerOutcomeWhenRepairIsUnaffordable(
            String limit, DeepSeekProtocolSmoke.Budget budget) throws IOException {
        try (var stub = new Stub(List.of(new Reply(200, completion(PROVIDER_PRIVATE_TEXT, STOP, null))))) {
            assertThat(run(stub, budget).outcome()).as(limit).isEqualTo(Outcome.INVALID_RESPONSE);
            assertThat(stub.requests).hasSize(1);
        }
    }

    private static Stream<Arguments> insufficientRepairBudgets() {
        return Stream.of(
                Arguments.of("requests", new DeepSeekProtocolSmoke.Budget(1, 2, 10_000, 2_000, USD, ELAPSED)),
                Arguments.of("estimated input", new DeepSeekProtocolSmoke.Budget(5, 2, 900, 2_000, USD, ELAPSED)),
                Arguments.of("allowed output", new DeepSeekProtocolSmoke.Budget(
                        5, 2, 10_000, MAX_OUTPUT_PER_REQUEST, USD, ELAPSED)),
                Arguments.of("estimated charge", new DeepSeekProtocolSmoke.Budget(
                        5, 2, 10_000, 2_000, new BigDecimal("0.00068"), ELAPSED)));
    }

    @Test
    void rejectsNonSyntheticInputsAndExpiredInvocationsBeforeSending() throws IOException {
        try (var stub = new Stub(List.of())) {
            var denied = smoke(stub, BUDGET).run("private-repository");
            assertThat(denied.outcome()).isEqualTo(Outcome.INPUT_REJECTED);
            assertThat(denied.attempts()).isEmpty();

            var expired = new DeepSeekProtocolSmoke.Budget(5, 2, 10_000, 2_000, USD, Duration.ZERO);
            assertThat(run(stub, expired).outcome()).isEqualTo(Outcome.BUDGET_EXHAUSTED);
            assertThat(stub.requests).isEmpty();
        }
    }

    @Test
    void declaresTheLiveOptInConditionOnTheTestAndRequiresACredential() throws NoSuchMethodException {
        var entry = DeepSeekLiveSmokeTest.class.getDeclaredMethod("exercisesTheFixedSyntheticFixture");
        assertThat(entry.getAnnotation(Test.class)).isNotNull();
        var gate = entry.getAnnotation(EnabledIfSystemProperty.class);
        assertThat(gate).isNotNull();
        assertThat(gate.named()).isEqualTo("deepseek.live");
        assertThat(gate.matches()).isEqualTo("true");
        assertThatThrownBy(() -> DeepSeekLiveSmokeTest.requireCredential(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("DEEPSEEK_API_KEY");
        assertThatThrownBy(() -> DeepSeekLiveSmokeTest.requireCredential(" "))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("DEEPSEEK_API_KEY");
    }

    @Test
    void runsTheLiveSmokePathWithTheConfiguredEndpointAndDefaultModel() throws IOException {
        try (var stub = new Stub(successfulTurn())) {
            var result = DeepSeekLiveSmokeTest.run(DeepSeekLiveSmokeTest.requireCredential(KEY),
                    "review.model.base-url=" + stub.baseUrl());
            DeepSeekLiveSmokeTest.assertProtocol(result);
            assertThat(stub.requests).hasSize(4).allSatisfy(request ->
                    assertThat(request.path("model").asText()).isEqualTo(DEFAULT_MODEL));
            assertThat(stub.authorizationHeaders).containsOnly("Bearer " + KEY);
        }
    }

    @ParameterizedTest
    @MethodSource("unexpectedLiveResults")
    void liveValidationRejectsAnUnexpectedPlanOrPositiveReasoning(String plan, String usage, String failsOn)
            throws IOException {
        var replies = new ArrayList<>(successfulTurn());
        replies.set(0, new Reply(200, completion(plan, STOP, usage)));
        try (var stub = new Stub(replies)) {
            var result = run(stub, BUDGET);
            assertThat(result.outcome()).isEqualTo(Outcome.COMPLETE);
            assertThatThrownBy(() -> DeepSeekLiveSmokeTest.assertProtocol(result))
                    .isInstanceOf(AssertionError.class).hasMessageContaining(failsOn);
        }
    }

    private static Stream<Arguments> unexpectedLiveResults() {
        return Stream.of(
                Arguments.of("""
                        {"action":"DELEGATE","agent":"CORRECTNESS","task":"Review fixture","rationale":"Review needed",
                         "topicKey":"fixture"}
                        """, null, "Planner action"),
                Arguments.of(VALID_PLAN, "{\"completion_tokens_details\":{\"reasoning_tokens\":1}}",
                        "reported reasoning tokens"));
    }

    @Test
    void rejectsPrematureReviewerStop() throws IOException {
        try (var stub = new Stub(List.of(new Reply(200, completion(VALID_PLAN, STOP, null)),
                new Reply(200, completion("Ending early.", STOP, null))))) {
            var result = run(stub, BUDGET);
            assertThat(result.outcome()).isEqualTo(Outcome.INVALID_RESPONSE);
            assertThat(result.toolCalls()).isZero();
            assertThat(stub.requests).hasSize(2);
        }
    }

    @Test
    void rejectsARepeatedToolCallId() throws IOException {
        try (var stub = new Stub(List.of(new Reply(200, completion(VALID_PLAN, STOP, null)),
                new Reply(200, toolCall(FIRST_ID, READ_TOOL, FIRST_ARGS)),
                new Reply(200, toolCall(FIRST_ID, READ_TOOL, SECOND_ARGS))))) {
            var result = run(stub, BUDGET);
            assertThat(result.outcome()).isEqualTo(Outcome.INVALID_TOOL_CALL);
            assertThat(result.toolCalls()).isEqualTo(1);
            assertThat(stub.requests).hasSize(3);
        }
    }

    @Test
    void rejectsAllToolCallsWhenOneRawEntryIsNotAFunction() throws IOException {
        var message = JSON.createObjectNode().put("role", "assistant").putNull("content");
        var calls = message.putArray("tool_calls");
        calls.addObject().put("id", FIRST_ID).put("type", "function")
                .putObject("function").put("name", READ_TOOL).put("arguments", FIRST_ARGS);
        calls.addObject().put("id", SECOND_ID).put("type", "other");
        try (var stub = new Stub(List.of(new Reply(200, completion(VALID_PLAN, STOP, null)),
                new Reply(200, envelope(message, "tool_calls", null))))) {
            var result = run(stub, BUDGET);
            assertThat(result.outcome()).isEqualTo(Outcome.INVALID_RESPONSE);
            assertThat(result.toolCalls()).isZero();
            assertThat(stub.requests).hasSize(2);
        }
    }

    @ParameterizedTest
    @MethodSource("invalidToolCalls")
    void rejectsInvalidToolCallsBeforeExecution(String tool, String args) throws IOException {
        try (var stub = new Stub(List.of(new Reply(200, completion(VALID_PLAN, STOP, null)),
                new Reply(200, toolCall(FIRST_ID, tool, args))))) {
            var result = run(stub, BUDGET);
            assertThat(result.outcome()).isEqualTo(Outcome.INVALID_TOOL_CALL);
            assertThat(result.toolCalls()).isZero();
            assertThat(stub.requests).hasSize(2);
        }
    }

    private static Stream<Arguments> invalidToolCalls() {
        return Stream.of(Arguments.of("unexpected_tool", FIRST_ARGS), Arguments.of(READ_TOOL, SECOND_ARGS),
                Arguments.of(READ_TOOL, "{\"part\":\"first\",\"path\":\"elsewhere\"}"),
                Arguments.of(READ_TOOL, "{\"part\":\"first\",\"part\":\"first\"}"),
                Arguments.of(READ_TOOL, "[]"));
    }

    @Test
    void enforcesToolAllowanceBeforeExecutingTheSecondCall() throws IOException {
        var budget = new DeepSeekProtocolSmoke.Budget(5, 1, 10_000, 2_000, USD, ELAPSED);
        try (var stub = new Stub(successfulTurn())) {
            var result = run(stub, budget);
            assertThat(result.outcome()).isEqualTo(Outcome.BUDGET_EXHAUSTED);
            assertThat(result.toolCalls()).isEqualTo(1);
            assertThat(stub.requests).hasSize(3);
        }
    }

    private static DeepSeekProtocolSmoke.Result run(Stub stub, DeepSeekProtocolSmoke.Budget budget) {
        return smoke(stub, budget).run(FIXTURE_ID);
    }

    private static DeepSeekProtocolSmoke smoke(Stub stub, DeepSeekProtocolSmoke.Budget budget) {
        return new DeepSeekProtocolSmoke(stub.baseUrl(), MODEL, KEY, budget, PRICE);
    }

    private static List<Reply> successfulTurn() {
        return List.of(new Reply(200, completion(VALID_PLAN, STOP, """
                        {"prompt_tokens":100,"completion_tokens":12,"prompt_cache_hit_tokens":40,
                         "prompt_cache_miss_tokens":60,"total_tokens":112,"completion_tokens_details":{"reasoning_tokens":0}}
                        """)),
                new Reply(200, toolCall(FIRST_ID, READ_TOOL, FIRST_ARGS)),
                new Reply(200, toolCall(SECOND_ID, READ_TOOL, SECOND_ARGS)),
                new Reply(200, completion("The fixture contains two counters.", STOP, null)));
    }

    private static String completion(String content, String finish, String usage) {
        return envelope(JSON.createObjectNode().put("role", "assistant").put("content", content), finish, usage);
    }

    private static String toolCall(String id, String tool, String args) {
        var message = JSON.createObjectNode().put("role", "assistant").putNull("content");
        message.putArray("tool_calls").addObject().put("id", id).put("type", "function")
                .putObject("function").put("name", tool).put("arguments", args);
        return envelope(message, "tool_calls", null);
    }

    private static String envelope(JsonNode message, String finish, String usage) {
        String usageField = usage == null ? "" : ",\"usage\":" + usage;
        return """
                {"id":"test-completion","object":"chat.completion","model":"%s","choices":[
                    {"index":0,"message":%s,"finish_reason":"%s"}]%s}
                """.formatted(MODEL, message, finish, usageField);
    }

    private record Reply(int status, String body) {
    }

    private static final class Stub implements AutoCloseable {

        private final HttpServer server;
        private final List<Reply> replies;
        private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        private final List<String> authorizationHeaders = new CopyOnWriteArrayList<>();
        private final List<String> requestLines = new CopyOnWriteArrayList<>();

        private Stub(List<Reply> replies) throws IOException {
            this.replies = replies;
            server = HttpServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                int index = requests.size();
                authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
                requestLines.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                requests.add(JSON.readTree(exchange.getRequestBody()));
                Reply reply = index < replies.size() ? replies.get(index) : new Reply(500, "Unexpected request");
                if (reply.status() == -1) {
                    // Leave the response pending past the caller's deadline.
                    exchange.sendResponseHeaders(200, 1);
                    exchange.getRequestBody().close();
                    pause();
                } else if (reply.status() != 0) {
                    send(exchange, reply);
                }
            }
        }

        private static void send(HttpExchange exchange, Reply reply) throws IOException {
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Retry-After", "0");
            exchange.getResponseHeaders().set("Location", COMPLETIONS_PATH);
            exchange.sendResponseHeaders(reply.status(), body.length);
            exchange.getResponseBody().write(body);
        }

        private static void pause() {
            try {
                Thread.sleep(Duration.ofSeconds(4));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private URI baseUrl() {
            return URI.create("http://" + LOOPBACK + ":" + server.getAddress().getPort());
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
