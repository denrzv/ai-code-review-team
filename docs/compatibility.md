# Compatibility

## FEATURE-001 bootstrap

The bootstrap is one Spring Boot executable. The build resolves the provider, Git and Temporal SDKs so later features share a verified dependency baseline.

| Component | Resolved version |
| --- | --- |
| Temurin Java | 25.0.5.1 in local verification, Java 25 required |
| Maven Wrapper scripts | 3.3.4, script-only |
| Maven distribution | 3.10.1 |
| Spring Boot | 4.1.3 |
| Temporal SDK and testing SDK | 1.41.1, via Temporal BOM |
| LangChain4j core and OpenAI adapter | 1.24.0, via LangChain4j BOM |
| Eclipse JGit | 7.8.1.202611041320-r |
| ArchUnit | 1.5.1, test scope |

### Jackson on the resolved classpath

Spring MVC uses Jackson 3.1.8 (`tools.jackson.core`). Temporal's default converter and LangChain4j use Jackson 2 (`com.fasterxml.jackson.core`). Boot initially selected Jackson 2.21.8. The POM overrides Boot's Jackson 2 BOM to 2.23.0, the version declared by the resolved LangChain4j POMs. The final tree contains Jackson 2.23.0 core, databind and JDK datatype modules, with annotations 2.23 shared by both Jackson lines. Local serialization tests round-trip records through Temporal's default converter and LangChain4j's mapper. Wire protocol and Workflow payload compatibility are checked in the respective integration features.

### Observed verification

On macOS ARM64 with Temurin 25.0.5.1 and `DEEPSEEK_API_KEY` unset:

- `./mvnw -v` reported Apache Maven 3.10.1. The wrapper pins the SHA-256 of the downloaded archive, which was also checked against the publisher's SHA-512.
- `./mvnw -B clean verify` passed all 27 tests and produced the executable JAR. The architecture tests demonstrate rejection of a non-JDK domain dependency and a Workflow dependency on the REST adapter.
- `./mvnw -B dependency:tree` resolved the SDKs and the Jackson versions above.
- `./mvnw -B validate` under Java 21 failed at Maven Enforcer with the required range `[25,26)`.
- The executable JAR started with no provider key or Temporal connection. Port 8080 was occupied locally, so the smoke run used `--server.port=0`. On the assigned loopback port, `GET /actuator/health` returned HTTP 200 with `status` set to `UP`, and `POST /api/reviews` returned HTTP 404.
- The packaged-JAR health step from the GitHub Actions workflow also passed locally.

## FEATURE-002 DeepSeek protocol

The smoke uses LangChain4j OpenAI 1.24.0 with an explicit OkHttp 4.12.0 transport. It reads `review.model.base-url` and `review.model.name` from Spring settings, which default to `https://api.deepseek.com` and `deepseek-flash`. The opt-in test reads the bearer credential from `DEEPSEEK_API_KEY`. The fixture is fixed and synthetic. Review settings supply the elapsed, token and estimated cost budgets and the dated price record for the selected model.

### Request and response contract

| Step | Wire behavior |
| --- | --- |
| Every completion | POST to `/chat/completions` relative to the configured base URL. Send the configured `model`, `thinking.type=disabled` and `max_tokens=256`. |
| Planner | Send `response_format.type=json_object`. Validate the five Planner fields locally. For blank, truncated, malformed or schema-invalid output, reissue the schema instruction once if the remaining budget covers it. |
| Reviewer | Supply one local function, `read_fixture`, with an allowlisted `part` argument. Expect one call for `first` and then one for `second`. Each tool result matches its assistant call's ID via `tool_call_id`. Keep both results in the final request history. |
| Usage | Preserve nullable counts from `usage` and optional token details. Estimate charges using the model's dated rates and known counts. Price unknown input at the higher configured input rate and use the request reservation where token totals are unavailable. |

The adapter sends no provider built-in tools. LangChain4j retries, transport retries, redirects and request and response logging are disabled. The transport marks POST bodies as one-shot, including for HTTP 503 with `Retry-After: 0`. Java admits at most five attempts, two fixture tool executions, 256 output tokens per request and one minute per invocation. Lower configured limits take effect first. The invocation deadline governs the caller's wait, including DNS, and cancels any outstanding request. The transport disables separate connection, read and write timeouts. The conservative input reservation uses the UTF-8 wire body size plus provider framing headroom, including the full message history and tool schema. The cap applies to estimated spend because billing and tokenization are provider-side. The test prints sanitized outcome, counts, elapsed time and dated usage estimates.

| Outcome | Classification |
| --- | --- |
| HTTP 400, other unsupported request statuses, or redirect | Permanent invalid request |
| HTTP 401 | Permanent authentication failure |
| HTTP 403/451, `content_filter` finish reason or explicit refusal | Permanent policy refusal, bypasses repair |
| HTTP 429 | Retryable rate limit |
| HTTP 5xx or an interrupted generation signaled by the provider | Retryable provider failure |
| HTTP 408, disconnect or I/O timeout | Retryable transport failure |
| Planner output still invalid after an affordable repair, or without room to repair | Typed invalid result |

### Verification

```sh
./mvnw -B -Dtest=DeepSeekProtocolTest test
./mvnw -B clean verify
./mvnw -B -Dtest=DeepSeekLiveSmokeTest -Ddeepseek.live=true test
```

The loopback suite checks wire fields, tool history, validation, limits, the live opt-in gate and request counts for status failures and early disconnects. The live smoke is skipped unless explicitly enabled. Once enabled, it fails before sending a request if `DEEPSEEK_API_KEY` is missing or blank. When credentials are present it can make bounded, billable requests.

On 2026-11-21, the live smoke passed on Temurin 25.0.5.1 against `https://api.deepseek.com` using `deepseek-flash`. DeepSeek accepted the thinking-disabled requests, returned a valid Planner `COMPLETE` decision, and completed the reviewer turn with two sequential tool calls. The smoke completed in 4.846 seconds across four requests, without a repair. DeepSeek reported 1,239 input tokens (384 cache hits and 855 cache misses) and 139 output tokens. The configured rates estimated USD 0.000425604. Reasoning token counts were absent. The live run exercised the successful path, and the loopback suite covers provider errors and malformed replies.

See the [DeepSeek Chat Completions API reference](https://api-docs.deepseek.com/api/create-chat-completion/) for the provider's documented fields.
