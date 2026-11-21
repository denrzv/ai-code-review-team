package dev.denrzv.codereview.adapter.llm;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.output.FinishReason;

import static dev.denrzv.codereview.adapter.llm.DeepSeekAdapter.Failure;
import static dev.denrzv.codereview.adapter.llm.DeepSeekAdapter.Reply;
import static dev.denrzv.codereview.adapter.llm.DeepSeekAdapter.Usage;

final class DeepSeekProtocolSmoke {

    static final String FIXTURE_ID = "synthetic-review-v1";
    static final int MAX_OUTPUT_PER_REQUEST = 256;
    static final int MAX_REQUESTS = 5;
    static final int REQUIRED_TOOL_CALLS = 2;
    static final Duration MAX_ELAPSED = Duration.ofMinutes(1);
    private static final String TOOL_NAME = "read_fixture";
    private static final String PART = "part";
    private static final String FIRST = "first";
    private static final String SECOND = "second";
    private static final List<String> PARTS = List.of(FIRST, SECOND);
    private static final String PLAN_PROMPT = """
            This synthetic review fixture is complete. Reply with a JSON object with exactly these fields:
            action (COMPLETE), agent (null), task (null), rationale (a short string), topicKey (null).
            Example: {"action":"COMPLETE","agent":null,"task":null,"rationale":"Fixture ready","topicKey":null}
            """;
    private static final String REPAIR_PROMPT = """
            Respond again with a single JSON object using all five required fields. Use JSON null as instructed.
            """;
    private static final String REVIEW_PROMPT = """
            Read the fixed synthetic fixture by calling read_fixture with part "first".
            After receiving that result, call read_fixture with part "second".
            Issue one call per response. After both results, reply in one sentence.
            """;
    private static final ToolSpecification FIXTURE_TOOL = ToolSpecification.builder()
            .name(TOOL_NAME)
            .description("Reads one part of the fixed synthetic fixture")
            .parameters(JsonObjectSchema.builder()
                    .addEnumProperty(PART, PARTS).required(PART).additionalProperties(false).build())
            .build();
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);

    private final DeepSeekAdapter adapter;
    private final Budget budget;
    private final Price price;

    DeepSeekProtocolSmoke(URI baseUrl, String modelName, String apiKey, Budget budget, Price price) {
        adapter = new DeepSeekAdapter(baseUrl, modelName, apiKey);
        this.budget = Objects.requireNonNull(budget);
        this.price = Objects.requireNonNull(price);
    }

    Result run(String fixtureId) {
        var invocation = new Invocation();
        if (!FIXTURE_ID.equals(fixtureId)) {
            return invocation.result(Outcome.INPUT_REJECTED, null);
        }
        PlannerResult planner = runPlanner(invocation);
        if (planner.outcome() != Outcome.COMPLETE) {
            return invocation.result(planner.outcome(), null);
        }
        return invocation.result(runReviewer(invocation), planner.decision());
    }

    private static PlannerResult runPlanner(Invocation invocation) {
        var prompt = UserMessage.from(PLAN_PROMPT);
        var first = invocation.send(List.of(prompt), true);
        var plan = planFrom(first);
        if (plan.outcome() != Outcome.INVALID_RESPONSE) {
            return plan;
        }

        // Reissue the fixed schema instruction without echoing any untrusted response text.
        int priorAttempts = invocation.attempts.size();
        var repair = invocation.send(List.of(prompt, UserMessage.from(REPAIR_PROMPT)), true);
        return repair.failure() == Failure.BUDGET_EXHAUSTED && invocation.attempts.size() == priorAttempts
                ? plan : planFrom(repair);
    }

    private static PlannerResult planFrom(Reply reply) {
        if (reply.failure() != null) {
            return new PlannerResult(Outcome.from(reply.failure()), null);
        }
        var response = reply.response();
        if (response.finishReason() != FinishReason.STOP || response.aiMessage().hasToolExecutionRequests()) {
            return new PlannerResult(Outcome.INVALID_RESPONSE, null);
        }
        PlanDecision decision = parsePlan(parseObject(response.aiMessage().text()));
        return decision == null ? new PlannerResult(Outcome.INVALID_RESPONSE, null)
                : new PlannerResult(Outcome.COMPLETE, decision);
    }

    private static PlanDecision parsePlan(JsonNode document) {
        if (document == null || document.size() != 5 || !shortText(document.path("rationale"))) {
            return null;
        }
        var action = document.path("action").asText();
        var agent = document.path("agent");
        var task = document.path("task");
        var topicKey = document.path("topicKey");
        if (PlanAction.COMPLETE.name().equals(action) && agent.isNull() && task.isNull() && topicKey.isNull()) {
            return new PlanDecision(PlanAction.COMPLETE, null);
        }
        if (PlanAction.DELEGATE.name().equals(action) && agent.isTextual() && shortText(task) && shortText(topicKey)) {
            try {
                return new PlanDecision(PlanAction.DELEGATE, PlanAgent.valueOf(agent.textValue()));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean shortText(JsonNode field) {
        return field.isTextual() && field.textValue().length() <= MAX_OUTPUT_PER_REQUEST && !field.textValue().isBlank();
    }

    private static Outcome runReviewer(Invocation invocation) {
        List<ChatMessage> history = new ArrayList<>();
        history.add(UserMessage.from(REVIEW_PROMPT));
        List<String> priorCallIds = new ArrayList<>();
        while (true) {
            var reply = invocation.send(history, false);
            if (reply.failure() != null) {
                return Outcome.from(reply.failure());
            }
            var response = reply.response();
            if (response.finishReason() != FinishReason.TOOL_EXECUTION) {
                return finishReviewer(response.finishReason(), response.aiMessage(), priorCallIds.size());
            }
            var outcome = executeTool(invocation, history, priorCallIds, response.aiMessage().toolExecutionRequests());
            if (outcome != Outcome.COMPLETE) {
                return outcome;
            }
        }
    }

    private static Outcome finishReviewer(FinishReason reason, AiMessage reply, int toolCalls) {
        return reason == FinishReason.STOP && !reply.hasToolExecutionRequests() && toolCalls == REQUIRED_TOOL_CALLS
                && reply.text() != null && !reply.text().isBlank() ? Outcome.COMPLETE : Outcome.INVALID_RESPONSE;
    }

    private static Outcome executeTool(
            Invocation invocation, List<ChatMessage> history, List<String> priorCallIds, List<ToolExecutionRequest> calls) {
        if (!invocation.canUseTool()) {
            return Outcome.BUDGET_EXHAUSTED;
        }
        if (calls.size() != 1 || priorCallIds.size() >= PARTS.size()) {
            return Outcome.INVALID_TOOL_CALL;
        }
        var call = calls.getFirst();
        String part = PARTS.get(priorCallIds.size());
        if (!validCall(call, part, priorCallIds)) {
            return Outcome.INVALID_TOOL_CALL;
        }

        var accepted = ToolExecutionRequest.builder().id(call.id()).name(TOOL_NAME)
                .arguments(JSON.createObjectNode().put(PART, part).toString()).build();
        String result = FIRST.equals(part) ? "int apples = 2;" : "int pears = 3;";
        invocation.toolCalls++;
        priorCallIds.add(accepted.id());
        history.add(AiMessage.from(accepted));
        history.add(ToolExecutionResultMessage.from(accepted, result));
        return Outcome.COMPLETE;
    }

    private static boolean validCall(ToolExecutionRequest call, String part, List<String> priorCallIds) {
        if (!TOOL_NAME.equals(call.name()) || call.id() == null || !call.id().matches("[A-Za-z0-9_-]{1,128}")
                || priorCallIds.contains(call.id())) {
            return false;
        }
        var args = parseObject(call.arguments());
        return args != null && args.size() == 1 && part.equals(args.path(PART).textValue());
    }

    private static JsonNode parseObject(String value) {
        if (value == null || value.length() > 2_048) {
            return null;
        }
        try {
            var parsed = JSON.readTree(value);
            return parsed != null && parsed.isObject() ? parsed : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    enum Outcome {
        COMPLETE, INPUT_REJECTED, INVALID_RESPONSE, INVALID_TOOL_CALL, BUDGET_EXHAUSTED,
        INVALID_REQUEST, AUTHENTICATION, POLICY_REFUSAL, RATE_LIMIT, TRANSIENT_PROVIDER, TRANSIENT_TRANSPORT;

        private static Outcome from(Failure failure) {
            return switch (failure) {
                case INVALID_REQUEST -> INVALID_REQUEST;
                case AUTHENTICATION -> AUTHENTICATION;
                case POLICY_REFUSAL -> POLICY_REFUSAL;
                case RATE_LIMIT -> RATE_LIMIT;
                case TRANSIENT_PROVIDER -> TRANSIENT_PROVIDER;
                case TRANSIENT_TRANSPORT -> TRANSIENT_TRANSPORT;
                case INVALID_RESPONSE -> INVALID_RESPONSE;
                case BUDGET_EXHAUSTED -> BUDGET_EXHAUSTED;
            };
        }

        boolean retryable() {
            return this == RATE_LIMIT || this == TRANSIENT_PROVIDER || this == TRANSIENT_TRANSPORT;
        }
    }

    enum PlanAction { COMPLETE, DELEGATE }
    enum PlanAgent { CORRECTNESS, SECURITY, PERFORMANCE }

    record PlanDecision(PlanAction action, PlanAgent agent) {
    }

    private record PlannerResult(Outcome outcome, PlanDecision decision) {
    }

    record Result(String model, Outcome outcome, PlanDecision decision, int toolCalls,
                  Duration elapsed, LocalDate pricesAsOf, List<Attempt> attempts) {
    }

    record Attempt(int reservedInputTokens, int reservedOutputTokens, BigDecimal reservedUsd,
                   Usage observed, BigDecimal estimatedUsd) {
    }

    record Budget(int requests, int toolCalls, int inputTokens, int outputTokens, BigDecimal usd, Duration elapsed) {
        Budget {
            if (requests < 0 || requests > MAX_REQUESTS || toolCalls < 0 || toolCalls > REQUIRED_TOOL_CALLS
                    || inputTokens < 0 || outputTokens < 0 || usd.signum() < 0
                    || elapsed.isNegative() || elapsed.compareTo(MAX_ELAPSED) > 0) {
                throw new IllegalArgumentException("Invalid smoke budget");
            }
        }
    }

    record Price(LocalDate asOf, BigDecimal cacheHitUsdPerMillion, BigDecimal cacheMissUsdPerMillion,
                 BigDecimal outputUsdPerMillion) {
        Price {
            Objects.requireNonNull(asOf);
            if (cacheHitUsdPerMillion.signum() < 0 || cacheMissUsdPerMillion.signum() < 0
                    || outputUsdPerMillion.signum() < 0) {
                throw new IllegalArgumentException("Negative pricing is invalid");
            }
        }

        private BigDecimal reserveCost(int input, int output) {
            return charge(input, highestInputRate()).add(charge(output, outputUsdPerMillion));
        }

        private BigDecimal estimateCost(Usage usage, Reservation reservation) {
            int output = usage.output() == null ? MAX_OUTPUT_PER_REQUEST : usage.output();
            int hit = knownCacheCount(usage.cacheHit(), usage.input(), usage.cacheMiss());
            int miss = knownCacheCount(usage.cacheMiss(), usage.input(), usage.cacheHit());
            long pricedInput = (long) hit + miss;
            long unknown = Math.max(0, inputForEstimate(usage, reservation, pricedInput) - pricedInput);
            return charge(hit, cacheHitUsdPerMillion).add(charge(miss, cacheMissUsdPerMillion))
                    .add(charge(unknown, highestInputRate())).add(charge(output, outputUsdPerMillion));
        }

        private static int knownCacheCount(Integer count, Integer total, Integer otherCount) {
            if (count != null) {
                return count;
            }
            return total != null && otherCount != null ? Math.max(0, total - otherCount) : 0;
        }

        private static long inputForEstimate(Usage usage, Reservation reservation, long pricedInput) {
            if (usage.input() != null) {
                return usage.input();
            }
            return usage.cacheHit() != null && usage.cacheMiss() != null ? pricedInput : reservation.inputTokens();
        }

        private BigDecimal highestInputRate() {
            return cacheHitUsdPerMillion.max(cacheMissUsdPerMillion);
        }

        private static BigDecimal charge(long tokens, BigDecimal rate) {
            return rate.multiply(BigDecimal.valueOf(tokens)).movePointLeft(6);
        }
    }

    private record Reservation(int inputTokens, BigDecimal usd) {
    }

    private final class Invocation {
        private final long start = System.nanoTime();
        private final long deadline = start + budget.elapsed().toNanos();
        private final List<Attempt> attempts = new ArrayList<>();
        private int toolCalls;
        private int remainingInput = budget.inputTokens();
        private int remainingOutput = budget.outputTokens();
        private BigDecimal remainingUsd = budget.usd();
        private Reservation reserved;

        private Reply send(List<ChatMessage> messages, boolean planner) {
            if (attempts.size() >= budget.requests() || remainingOutput < MAX_OUTPUT_PER_REQUEST || expired()) {
                return new Reply(null, Failure.BUDGET_EXHAUSTED, Usage.UNKNOWN);
            }
            var request = ChatRequest.builder().messages(messages).maxOutputTokens(MAX_OUTPUT_PER_REQUEST);
            if (planner) {
                request.responseFormat(ResponseFormat.JSON);
            } else {
                request.toolSpecifications(FIXTURE_TOOL);
            }

            var reply = adapter.complete(request.build(), deadline, this::reserve);
            if (reserved == null || reply.failure() == Failure.BUDGET_EXHAUSTED) {
                return reply;
            }
            var observed = reply.usage();
            var attempt = new Attempt(reserved.inputTokens(), MAX_OUTPUT_PER_REQUEST, reserved.usd(), observed,
                    price.estimateCost(observed, reserved));
            attempts.add(attempt);
            reserved = null;
            if (canContinue(reply) && (expired() || exceedsReservation(attempt))) {
                return new Reply(null, Failure.BUDGET_EXHAUSTED, observed);
            }
            return reply;
        }

        private static boolean canContinue(Reply reply) {
            return reply.failure() == null || reply.failure() == Failure.INVALID_RESPONSE;
        }

        private static boolean exceedsReservation(Attempt attempt) {
            var usage = attempt.observed();
            long cacheTokens = (long) known(usage.cacheHit()) + known(usage.cacheMiss());
            long input = Math.max(cacheTokens, known(usage.input()));
            int output = Math.max(known(usage.output()), Math.max(known(usage.reasoning()), known(usage.safety())));
            long total = known(usage.total());
            boolean inputKnown = usage.input() != null || (usage.cacheHit() != null && usage.cacheMiss() != null);
            return input > attempt.reservedInputTokens()
                    || output > attempt.reservedOutputTokens()
                    || total > (long) attempt.reservedInputTokens() + attempt.reservedOutputTokens()
                    || (inputKnown && total - input > attempt.reservedOutputTokens())
                    || (usage.output() != null && total - usage.output() > attempt.reservedInputTokens())
                    || attempt.estimatedUsd().compareTo(attempt.reservedUsd()) > 0;
        }

        private static int known(Integer count) {
            return count == null ? 0 : count;
        }

        private boolean reserve(int inputEstimate) {
            BigDecimal cost = price.reserveCost(inputEstimate, MAX_OUTPUT_PER_REQUEST);
            if (inputEstimate > remainingInput || cost.compareTo(remainingUsd) > 0 || expired()) {
                return false;
            }
            remainingInput -= inputEstimate;
            remainingOutput -= MAX_OUTPUT_PER_REQUEST;
            remainingUsd = remainingUsd.subtract(cost);
            reserved = new Reservation(inputEstimate, cost);
            return true;
        }

        private boolean canUseTool() {
            return toolCalls < budget.toolCalls() && !expired();
        }

        private boolean expired() {
            return System.nanoTime() - deadline >= 0;
        }

        private Result result(Outcome outcome, PlanDecision decision) {
            return new Result(adapter.modelName(), outcome, decision, toolCalls,
                    Duration.ofNanos(System.nanoTime() - start), price.asOf(), List.copyOf(attempts));
        }
    }
}
