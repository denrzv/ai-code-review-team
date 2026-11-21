package dev.denrzv.codereview.adapter.llm;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntPredicate;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.BufferedSink;

final class DeepSeekAdapter {

    private static final int MAX_RESPONSE_BYTES = 32_768;
    private static final int PROVIDER_FRAMING_ALLOWANCE = 128;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json");
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .protocols(List.of(Protocol.HTTP_1_1))
            .connectTimeout(Duration.ZERO)
            .readTimeout(Duration.ZERO)
            .writeTimeout(Duration.ZERO)
            .addNetworkInterceptor(chain -> {
                if (chain.call().isCanceled()) {
                    throw new IOException("Provider call cancelled");
                }
                return chain.proceed(chain.request());
            })
            .build();

    private final URI baseUrl;
    private final String modelName;
    private final String apiKey;

    DeepSeekAdapter(URI baseUrl, String modelName, String apiKey) {
        if (!allowedEndpoint(baseUrl)) {
            throw new IllegalArgumentException("Invalid provider endpoint");
        }
        if (modelName == null || !modelName.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}")) {
            throw new IllegalArgumentException("Invalid provider model name");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Provider credential is required");
        }
        this.baseUrl = baseUrl;
        this.modelName = modelName;
        this.apiKey = apiKey;
    }

    private static boolean allowedEndpoint(URI uri) {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            return false;
        }
        return "https".equalsIgnoreCase(uri.getScheme())
                || ("http".equalsIgnoreCase(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()));
    }

    String modelName() {
        return modelName;
    }

    Reply complete(ChatRequest request, long deadlineNanos, IntPredicate reserve) {
        var transport = new SinglePostTransport(deadlineNanos, reserve);
        try {
            var response = OpenAiChatModel.builder()
                    .baseUrl(baseUrl.toString())
                    .modelName(modelName)
                    .apiKey(apiKey)
                    .customParameters(Map.of("thinking", Map.of("type", "disabled")))
                    .temperature(0.0)
                    .maxRetries(0)
                    .logRequests(false)
                    .logResponses(false)
                    .httpClientBuilder(transport)
                    .build()
                    .chat(request);
            return new Reply(response, null, transport.usage);
        } catch (RuntimeException ignored) {
            var failure = transport.failure == null ? Failure.INVALID_RESPONSE : transport.failure;
            return new Reply(null, failure, transport.usage);
        }
    }

    enum Failure {
        INVALID_REQUEST, AUTHENTICATION, POLICY_REFUSAL, RATE_LIMIT, TRANSIENT_PROVIDER,
        TRANSIENT_TRANSPORT, INVALID_RESPONSE, BUDGET_EXHAUSTED
    }

    record Usage(Integer input, Integer output, Integer cacheHit, Integer cacheMiss,
                 Integer total, Integer reasoning, Integer safety) {
        static final Usage UNKNOWN = new Usage(null, null, null, null, null, null, null);

        private static Usage from(JsonNode node) {
            Integer hit = count(node.path("prompt_cache_hit_tokens"));
            if (hit == null) {
                hit = count(node.path("prompt_tokens_details").path("cached_tokens"));
            }
            var outputDetails = node.path("completion_tokens_details");
            return new Usage(count(node.path("prompt_tokens")), count(node.path("completion_tokens")), hit,
                    count(node.path("prompt_cache_miss_tokens")), count(node.path("total_tokens")),
                    count(outputDetails.path("reasoning_tokens")), count(outputDetails.path("safety_tokens")));
        }

        private static Integer count(JsonNode node) {
            return node.isIntegralNumber() && node.canConvertToInt() && node.intValue() >= 0
                    ? node.intValue() : null;
        }
    }

    record Reply(ChatResponse response, Failure failure, Usage usage) {
        @Override
        public String toString() {
            return "Reply[failure=" + failure + ", usage=" + usage + "]";
        }
    }

    private record WireReply(int status, byte[] body) {
    }

    private static final class SinglePostTransport implements HttpClientBuilder, HttpClient {

        private final long deadlineNanos;
        private final IntPredicate reserve;
        private Failure failure;
        private Usage usage = Usage.UNKNOWN;

        private SinglePostTransport(long deadlineNanos, IntPredicate reserve) {
            this.deadlineNanos = deadlineNanos;
            this.reserve = reserve;
        }

        @Override
        public SuccessfulHttpResponse execute(HttpRequest request) {
            byte[] body = request.body().getBytes(StandardCharsets.UTF_8);
            var post = new Request.Builder().url(request.url()).post(new SingleUseBody(body));
            request.headers().forEach((name, values) -> values.forEach(value -> post.addHeader(name, value)));
            var call = HTTP.newCall(post.build());
            // One byte per token plus framing leaves headroom without needing the provider tokenizer.
            if (System.nanoTime() - deadlineNanos >= 0 || !reserve.test(body.length + PROVIDER_FRAMING_ALLOWANCE)) {
                throw stop(Failure.BUDGET_EXHAUSTED);
            }
            return inspect(await(call));
        }

        private WireReply await(Call call) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                throw stop(Failure.BUDGET_EXHAUSTED);
            }
            call.timeout().timeout(remaining, TimeUnit.NANOSECONDS);
            // DNS can block inside the synchronous OkHttp call even after cancellation.
            var task = new FutureTask<>(() -> readWireReply(call));
            Thread.startVirtualThread(task);
            try {
                return task.get(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw stop(Failure.TRANSIENT_TRANSPORT);
            } catch (ExecutionException | TimeoutException ignored) {
                throw stop(Failure.TRANSIENT_TRANSPORT);
            } finally {
                if (!task.isDone()) {
                    call.cancel();
                    task.cancel(true);
                }
            }
        }

        private static WireReply readWireReply(Call call) throws IOException {
            try (var response = call.execute()) {
                return new WireReply(response.code(),
                        response.isSuccessful() ? response.peekBody(MAX_RESPONSE_BYTES + 1).bytes() : null);
            }
        }

        private SuccessfulHttpResponse inspect(WireReply response) {
            byte[] body = response.body();
            if (body == null) {
                throw stop(classifyStatus(response.status()));
            }
            if (body.length > MAX_RESPONSE_BYTES) {
                throw stop(Failure.INVALID_RESPONSE);
            }
            JsonNode parsed;
            try {
                parsed = JSON.readTree(body);
            } catch (IOException ignored) {
                throw stop(Failure.INVALID_RESPONSE);
            }
            if (parsed == null || !parsed.isObject()) {
                throw stop(Failure.INVALID_RESPONSE);
            }
            usage = Usage.from(parsed.path("usage"));
            JsonNode choice = parsed.path("choices").path(0);
            JsonNode message = choice.path("message");
            String finish = choice.path("finish_reason").asText();
            if ("content_filter".equals(finish) || !message.path("refusal").asText("").isBlank()) {
                throw stop(Failure.POLICY_REFUSAL);
            }
            if ("insufficient_system_resource".equals(finish) || "aborted".equals(finish)) {
                throw stop(Failure.TRANSIENT_PROVIDER);
            }
            if (!message.path("server_tool_calls").isEmpty() || hasUnsupportedToolCall(message.path("tool_calls"))) {
                throw stop(Failure.INVALID_RESPONSE);
            }
            return SuccessfulHttpResponse.builder().statusCode(response.status()).body(body).build();
        }

        private static boolean hasUnsupportedToolCall(JsonNode calls) {
            if (!calls.isArray()) {
                return !calls.isMissingNode() && !calls.isNull();
            }
            for (JsonNode call : calls) {
                if (!"function".equals(call.path("type").asText())) {
                    return true;
                }
            }
            return false;
        }

        private static Failure classifyStatus(int status) {
            return switch (status) {
                case 401 -> Failure.AUTHENTICATION;
                case 403, 451 -> Failure.POLICY_REFUSAL;
                case 408 -> Failure.TRANSIENT_TRANSPORT;
                case 429 -> Failure.RATE_LIMIT;
                default -> status >= 500 ? Failure.TRANSIENT_PROVIDER : Failure.INVALID_REQUEST;
            };
        }

        private RuntimeException stop(Failure reason) {
            failure = reason;
            return new IllegalStateException(reason.name());
        }

        // The invocation deadline controls the sole transport call.
        @Override public Duration connectTimeout() { return null; }
        @Override public Duration readTimeout() { return null; }
        @Override public HttpClientBuilder connectTimeout(Duration timeout) { return this; }
        @Override public HttpClientBuilder readTimeout(Duration timeout) { return this; }
        @Override public HttpClient build() { return this; }

        @Override
        public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
            throw new UnsupportedOperationException("Streaming is disabled");
        }
    }

    private static final class SingleUseBody extends RequestBody {

        private final byte[] body;

        private SingleUseBody(byte[] body) {
            this.body = body;
        }

        @Override public MediaType contentType() { return JSON_MEDIA_TYPE; }
        @Override public long contentLength() { return body.length; }

        // Also prevents status-driven replays such as HTTP 503 with Retry-After: 0.
        @Override public boolean isOneShot() { return true; }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            sink.write(body);
        }
    }
}
