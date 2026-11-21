# Review: Currency and Reality Check (ARCHITECTURE-SPINE.md)

- **Reviewer lens:** Was each committed decision checked against the web or the project, or asserted from memory? This covers versions, whether each technology exists and fits, and the live defaults the spine relies on.
- **Date:** 2026-11-21
- **Method:**
  - Maven Central `maven-metadata.xml` for each artifact.
  - `javap` against the real 1.41.1 / 1.24.0 / 7.8.1 jars, downloaded into a scratch dir.
  - A runtime ArchUnit import of a Java 25 (class major 69) class on the local JDK, `openjdk 25.0.5.1`.
  - Temporal CLI and server source on GitHub.
  - Official Spring, Temporal and DeepSeek docs.

**Verdict: PASS WITH FIXES.**

- Every pinned version exists and is the latest in its line. Every Temporal and LangChain4j API the spine names exists.
- The spine records none of these checks. It has no "verified on" source per Stack row, so a reader cannot tell what was checked from what was assumed.
- Three live defaults contradict what the spine implicitly relies on:
  - Temporal's Workflow-Id reuse policy and the dev server's 24 h retention break AD-14.
  - DeepSeek turns thinking on by default.
  - DeepSeek has no `json_schema` response format.
- One error-mapping gap affects AD-7: 402, and client-side timeouts.

## Summary table

| # | Item | Status |
| --- | --- | --- |
| 1 | Spring Boot 4.1.3 latest 4.1.x, Java 25 | CONFIRMED (+ RISK: 4.2.0 GA is out) |
| 2 | Maven 3.10.1 | CONFIRMED |
| 3 | Temporal SDK 1.41.1 version + named APIs | CONFIRMED |
| 4 | Temporal Jackson 2 vs Boot 4 Jackson 3 | RISK (no clash, but two Jacksons + version skew) |
| 5 | AD-14 idempotency relies on `WorkflowExecutionAlreadyStarted` | **WRONG** (default reuse policy + dev retention) |
| 6 | `temporal server start-dev --db-filename` persists | CONFIRMED (+ retention RISK, see 5) |
| 7 | AD-16 heartbeat / detached scope | CONFIRMED (+ RISK: cancellation type) |
| 8 | LangChain4j 1.24.0 builder: baseUrl, maxRetries, timeout, customParameters, responseFormat, tools; `ChatModel` | CONFIRMED |
| 9 | Structured output via JSON-schema response format against DeepSeek | RISK, close to WRONG (DeepSeek supports only `json_object`) |
| 10 | AD-7 error classification vs real DeepSeek codes + LangChain4j mapping | RISK (402 missing; client timeouts are not `TimeoutException`) |
| 11 | DeepSeek `deepseek-flash`, non-thinking, tools, base URL | CONFIRMED, with RISK: thinking is **on by default** |
| 12 | DeepSeek price table (AD-18) | RISK (peak/off-peak pricing) |
| 13 | JGit 7.8.1.202611041320-r on Java 25 | CONFIRMED |
| 14 | ArchUnit 1.5.1 reads Java 25 classes | CONFIRMED (tested) |
| 15 | RFC 9457 ProblemDetail in Boot 4 | CONFIRMED (+ minor RISK on `type` value) |
| 16 | LangChain4j Jackson 2.23.0 vs Boot-managed 2.21.8 | RISK |
| 17 | Spine records no verification sources | RISK (process) |

---

## 1. Spring Boot 4.1.3 on Java 25: CONFIRMED, with a RISK

**Evidence:**
- Maven Central `org/springframework/boot/spring-boot` lists `4.1.1, 4.1.2, 4.1.3, 4.2.0-M1 … 4.2.0`. So **4.1.3 is the last 4.1.x**.
- 4.1.3 and **4.2.0 GA** were both published on 2026-11-19. Metadata `<release>` is `4.2.0`.
- The docs say: "Spring Boot 4.1.3 requires at least Java 17 and is compatible with versions up to and including Java 27". It needs Spring Framework 7.0.11+ and Maven 3.6.3+.

**Sources:**
- https://repo1.maven.org/maven2/org/springframework/boot/spring-boot/maven-metadata.xml
- https://docs.spring.io/spring-boot/4.1/system-requirements.html

**RISKS:**
- 4.2.0 GA shipped two days ago, so "4.1.x" is no longer the current line. Project CLAUDE.md rule 9 mandates 4.1.x, so this is a choice to keep, not an error. Still, the spine should say so explicitly.
- `spring-boot-starter-parent` 4.1.3 defaults `java.version` to 17. Java 25 must be set explicitly.

**Proposed spine fix:** In the Stack row, add: "4.1.3 (latest 4.1.x as of 2026-11-21; 4.2.0 GA exists but is not adopted, per CLAUDE.md rule 9). Set `<java.version>25</java.version>`."

## 2. Maven 3.10.1: CONFIRMED

**Evidence:**
- `maven-core` metadata contains `3.10.0-rc-1, 3.10.0, 3.10.1`.
- Maven 4 is still at `4.0.0-rc-7`, not GA, so 3.10.1 is the right choice.

**Source:** https://repo1.maven.org/maven2/org/apache/maven/maven-core/maven-metadata.xml

**Proposed spine fix:** None. Optionally pin it through the Maven Wrapper (`.mvn/wrapper/maven-wrapper.properties`).

## 3. Temporal Java SDK 1.41.1 and the named APIs: CONFIRMED

**Evidence:**
- Maven Central `temporal-sdk`, `temporal-testing` and `temporal-bom` all have `<release>` 1.41.1.
- GitHub release v1.41.1 was published on 2026-11-16.
- The jar's class files are major 52 (Java 8 bytecode), which runs on 25.

`javap` against the 1.41.1 jars found every API the spine names:

| Spine reference | Found in 1.41.1 |
| --- | --- |
| `Workflow.currentTimeMillis()` | `public static long currentTimeMillis()` |
| `Workflow.getVersion` | `public static int getVersion(String, int, int)` |
| Detached cancellation scope | `Workflow.newDetachedCancellationScope(Runnable)` |
| Non-retryable `ApplicationFailure` | `ApplicationFailure.newNonRetryableFailure(String, String, Object...)`, `newNonRetryableFailureWithCause`, `newBuilder()` |
| `doNotRetry` | `RetryOptions.Builder.setDoNotRetry(String...)` |
| Memo | `WorkflowOptions.Builder.setMemo(Map)`; `WorkflowExecutionMetadata.getMemo(String, Class)` |
| Describe | `WorkflowStub.describe() → WorkflowExecutionDescription` (`getStatus()`, `getMemo`) |
| Already started | `io.temporal.client.WorkflowExecutionAlreadyStarted` |
| Heartbeat | `ActivityOptions.Builder.setHeartbeatTimeout(Duration)`; `ActivityExecutionContext.heartbeat(V)` |
| `WorkflowReplayer` | `io.temporal.testing.WorkflowReplayer.replayWorkflowExecutionFromResource(...)` in `temporal-testing` |
| `TestWorkflowEnvironment` | `io.temporal.testing.TestWorkflowEnvironment` in `temporal-testing` |

**Sources:**
- https://repo1.maven.org/maven2/io/temporal/temporal-sdk/maven-metadata.xml
- https://github.com/temporalio/sdk-java/releases/tag/v1.41.1

**Proposed spine fix:** None for the APIs. Two notes:
- AD-7's `doNotRetry` matches on the failure **type string**. The convention "type = exception simple name" is consistent with that.
- `ApplicationFailure.newNonRetryableFailure` already makes a failure non-retryable without the `doNotRetry` list. Pick one mechanism, or state that both are used deliberately as defense in depth.

## 4. Temporal (Jackson 2) vs Spring Boot 4 (Jackson 3): RISK, no hard clash

**Evidence:**
- `temporal-sdk-1.41.1.pom` depends on `com.fasterxml.jackson.core:jackson-databind` (Jackson 2) at compile scope, with jsr310/jdk8 modules at runtime. It also has an **optional** dependency on `tools.jackson.core:jackson-databind [3.0.4,)`.
- `DefaultDataConverter` is still built on `JacksonJsonPayloadConverter`, which is Jackson 2 (checked in bytecode).
- The SDK also ships `io.temporal.common.converter.Jackson3JsonPayloadConverter` with constructors `()`, `(boolean)` and `(tools.jackson.databind.json.JsonMapper)`. The v1.41.0 release notes fix its multi-release-jar API.
- `spring-boot-dependencies:4.1.3` imports **both** the `com.fasterxml.jackson:jackson-bom` 2.21.8 BOM (`jackson-2-bom.version`) and the `tools.jackson:jackson-bom` 3.1.8 BOM.
- Jackson 2 and 3 use different packages (`com.fasterxml` vs `tools.jackson`), so they coexist without a classpath clash. Boot's BOM silently upgrades Temporal's declared Jackson 2.15.4 to 2.21.8, which is benign.

**The risk:**
- The spine says "Serialization: Jackson" as if there were one. In practice:
  - Activity payloads and history go through Temporal's Jackson 2 mapper.
  - REST and report writing would default to Boot's Jackson 3 `JsonMapper`.
- The two mappers differ in defaults. Jackson 3 changed several defaults, for example around date/time, so the same record could serialize differently in history and in `report.json`.
- AD-13 claims "identical bytes" and AD-12 claims "Jackson-serializable records". Both are only true per mapper.

**Sources:**
- https://repo1.maven.org/maven2/io/temporal/temporal-sdk/1.41.1/temporal-sdk-1.41.1.pom
- https://github.com/temporalio/sdk-java/releases/tag/v1.41.0
- https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.3/spring-boot-dependencies-4.1.3.pom

**Proposed spine fix:** Add a convention: "Temporal's `WorkflowClient` is built with `DefaultDataConverter.newDefaultInstance().withPayloadConverterOverrides(new Jackson3JsonPayloadConverter(sharedJsonMapper))`, so one Jackson 3 `JsonMapper` serves Temporal payloads, REST and reports." Alternatively, explicitly accept Jackson 2 for Temporal and pin report writing to one named mapper. Either way, add a test that round-trips every Activity record through the configured `DataConverter`.

## 5. AD-14 idempotent submission via `WorkflowExecutionAlreadyStarted`: **WRONG** as written

**Evidence:**
- Temporal docs: the default **Workflow Id Reuse Policy is Allow Duplicate**, and "a new execution can start with the same ID as a closed execution, whatever that execution's final status was." The default Conflict Policy is Fail, but it applies only to **open** executions. Reuse policies also "only check closed executions that are still retained in the Namespace."
- The Temporal server's `schema/sqlite/setup.go` (`NewNamespaceConfig`, the code path `temporal server start-dev` uses) sets `Retention: timestamp.DurationFromHours(24)`. So the dev server keeps closed runs for **24 h**, not 30 days.

**Consequences:**
- With the defaults, re-submitting a `reviewId` after its run has closed starts a **new** run. A different payload hash also starts a new run instead of returning 409.
- AD-14 therefore holds only while the run is open.
- After 24 h, describe returns NOT_FOUND, so status lookups for old reviews also break.

**Sources:**
- https://docs.temporal.io/workflow-execution/workflowid-runid
- https://github.com/temporalio/temporal/blob/main/schema/sqlite/setup.go

**Proposed spine fix:** Amend AD-14:
- "Start with `WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE` and `WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL`, set explicitly and never left to the defaults."
- "Idempotency and status are guaranteed only within namespace retention. Configure the dev namespace retention (e.g. `temporal operator namespace update --retention 30d default`, or create a dedicated namespace) and document it."
- "After retention expires, status falls back to `manifest.json` on disk, or returns 404, and a re-submit is treated as new."

Add tests for "same id after completion, same hash → 200" and "different hash → 409".

## 6. `temporal server start-dev --db-filename`: CONFIRMED

**Evidence:**
- The Temporal CLI `commands.yaml` documents `db-filename` (short `-f`): "Path to file for persistent Temporal state store. By default, Workflow Executions are lost when the server process dies."
- The latest CLI is v1.10.1 (2026-11-13).

**Sources:**
- https://github.com/temporalio/cli/blob/main/internal/temporalcli/commands.yaml
- https://github.com/temporalio/cli/releases/tag/v1.10.1

**RISK:**
- The spine's "latest CLI" is unpinned, which conflicts with the reproducibility NFR.
- The 24 h retention (item 5) still applies with a DB file.

**Proposed spine fix:** In the Stack row, pin "Temporal CLI 1.10.1 (`temporal server start-dev --db-filename temporal.db`, namespace retention set per AD-14)".

## 7. AD-16 cancellation: CONFIRMED APIs, with a RISK on semantics

**Evidence:** `setHeartbeatTimeout`, `setCancellationType`, `heartbeat(V)` and `newDetachedCancellationScope` all exist (item 3).

**RISK:**
- Activity cancellation reaches an Activity only through a heartbeat, which the spine covers.
- The default `ActivityCancellationType` is `TRY_CANCEL`. The Workflow does not wait for the Activity to stop, so `validateAndReport` can run while a specialist is still in a 60 s LLM call that bills.
- The SDK also throttles heartbeats, to a fraction of the heartbeat timeout.

**Proposed spine fix:** Add to AD-16: "`reviewSpecialist`/`planNext` use `ActivityCancellationType.WAIT_CANCELLATION_COMPLETED`, so the final report includes the cancelled attempt's usage. The cancellation latency bound is about the LLM call timeout (60 s), not the heartbeat timeout."

## 8. LangChain4j 1.24.0: CONFIRMED

**Evidence:**
- Maven Central `langchain4j`, `langchain4j-open-ai` and `langchain4j-bom` all have `<release>` 1.24.0 (2026-11-18). The class files are major 61 (Java 17).
- `javap` of `OpenAiChatModel$OpenAiChatModelBuilder` (1.24.0) shows:
  - `baseUrl(String)`, `maxRetries(Integer)`, `timeout(Duration)`
  - `customParameters(Map<String,Object>)`, `customHeaders(...)`
  - `responseFormat(ResponseFormat)`, `responseFormat(String)`
  - `strictJsonSchema(Boolean)`, `strictTools(Boolean)`, `parallelToolCalls(Boolean)`
  - `reasoningEffort(String)`, `returnThinking(Boolean)`, `sendThinking(Boolean[, String])`
  - `listeners(...)`, `httpClientBuilder(...)`
- `dev.langchain4j.model.chat.ChatModel` is the interface name. It has `chat(ChatRequest) → ChatResponse`, and `ToolSpecification` exists.
- So "custom request params for DeepSeek thinking disable" can be done with `customParameters(Map.of("thinking", Map.of("type","disabled")))`.

**Sources:**
- https://repo1.maven.org/maven2/dev/langchain4j/langchain4j-open-ai/maven-metadata.xml
- https://repo1.maven.org/maven2/dev/langchain4j/langchain4j-open-ai/1.24.0/

**Proposed spine fix:** None for the version.

## 9. JSON-schema structured output against DeepSeek: RISK, close to WRONG

**Evidence:**
- The DeepSeek JSON Output guide documents only `response_format: {"type": "json_object"}`, with no `json_schema` type.
- The prompt must contain the word "json".
- If the output is not valid JSON, "content may appear empty even when finish_reason is stop".
- LangChain4j can send a JSON-schema `ResponseFormat` (item 8), but DeepSeek's OpenAI-compatible API will likely reject it or ignore it.

**Source:** https://api-docs.deepseek.com/guides/json_mode

**Proposed spine fix:**
- Make AD-18 explicit: "Structured output uses `response_format=json_object` plus Jackson parsing against the fixed schema. JSON-schema response format is not used: DeepSeek does not support it."
- Add to AD-6: "Empty or blank `content` counts as malformed output (one repair, then a returned value)."
- If schema enforcement is wanted, the only DeepSeek option is **strict tool calling** on the beta endpoint `https://api.deepseek.com/beta`. Its JSON-Schema subset does not support `minLength`/`maxLength`/`minItems`/`maxItems`, and all properties must be `required`. Leave it to the FEATURE-002 spike.

## 10. AD-7 error classification vs the real codes and LangChain4j mapping: RISK

**Evidence:**
- The DeepSeek error-codes page lists 400, 401, **402 Insufficient balance**, 408, 413, 422, 429, 500, **503 Server overloaded** and 504. It has no 403 or 404.
- LangChain4j 1.24.0 `ExceptionMapper$DefaultExceptionMapper` (bytecode) maps:
  - 500–599 → `InternalServerException`
  - 401/403 → `AuthenticationException`
  - 404 → `ModelNotFoundException`
  - 408 → `TimeoutException`
  - 429 → `RateLimitException`
  - any other 400–499, including 402 → `InvalidRequestException`
  - non-HTTP causes → generic `LangChain4jException`, except DNS failure → `UnresolvedModelServerException`
- So a **client-side read timeout** (`java.net.http.HttpTimeoutException`) arrives as a plain `LangChain4jException`, not as `TimeoutException`. Code that classifies by LangChain4j type alone would call it permanent or unknown.

**Sources:**
- https://api-docs.deepseek.com/quick_start/error_codes
- `langchain4j-core-1.24.0.jar` (`dev/langchain4j/internal/ExceptionMapper$DefaultExceptionMapper`)

**Proposed spine fix:** Rewrite the AD-7 classification as:
- Retryable: `RateLimitException`, `InternalServerException` (500/503/504), `TimeoutException`, and any exception whose cause chain contains `java.net.http.HttpTimeoutException`, `java.net.http.HttpConnectTimeoutException` or `IOException`.
- Non-retryable: `AuthenticationException`, `ModelNotFoundException`, `InvalidRequestException` (covers 400/402/413/422) and `ContentFilteredException`.
- Name **402 (insufficient balance)** explicitly as non-retryable, with its own reason detail.

The FEATURE-002 spike should confirm the actual body shapes.

## 11. DeepSeek model, thinking, tools, base URL: CONFIRMED, with a RISK

**Evidence:**
- From api-docs.deepseek.com:
  - The OpenAI-compatible base URL is `https://api.deepseek.com` (Anthropic-compatible: `/anthropic`; beta features: `/beta`).
  - `deepseek-flash` exists, "now served by DeepSeek-V4.1-Flash-1017". `deepseek-v4-pro` also exists.
  - The legacy `deepseek-v4-flash` is retired and aliased.
- The tool-calls guide's example uses `model="deepseek-flash"` with `tools` in non-thinking mode.
- The thinking guide says the switch is `{"thinking": {"type": "disabled"}}`. It also says: "**Thinking mode is enabled by default**, with the default effort being `high`."
- In thinking mode with `tools`, "the `reasoning_content` must be fully passed back … If your code does not correctly pass back `reasoning_content`, the API will return a 400 error."

**Sources:**
- https://api-docs.deepseek.com/
- https://api-docs.deepseek.com/guides/thinking_mode
- https://api-docs.deepseek.com/guides/tool_calls

**RISK:**
- If the disable parameter is ever omitted or dropped (for example a `customParameters` merge bug), the tool loop gets 400s, which AD-7 classifies as permanent, plus higher cost from thinking tokens.
- `temperature` is ignored in thinking mode, so determinism settings silently stop working.

**Proposed spine fix:** Move "thinking off" out of Deferred and into AD-18:
- "Every request sets `thinking: {type: disabled}` via `OpenAiChatModel.builder().customParameters(...)`."
- "A startup or contract test asserts the outgoing request body contains it, and that the response has no `reasoning_content`."
- Keep only the residual wire details in the FEATURE-002 spike.

## 12. Price table (AD-18 / AD-8): RISK

**Evidence:**
- The DeepSeek pricing page gives `deepseek-flash` prices per 1M tokens. Off-peak: cache hit $0.003, cache miss $0.15, output $0.60. **Peak (2×)**: $0.006 / $0.30 / $1.20.
- Peak hours are 01:00–04:00 and 06:00–10:00 UTC, Mon–Fri.
- Context is 1M tokens.

**Source:** https://api-docs.deepseek.com/quick_start/pricing

**Risk:** A single-rate price table under-estimates cost by up to 2× during peak hours. AD-8 cost ceilings (USD 0.50/run, 0.10/invocation) then stop being upper bounds.

**Proposed spine fix:** In AD-18, the price table carries `{cacheHitIn, cacheMissIn, out}` × `{peak, offPeak}` plus the peak windows. Budget enforcement uses the **peak** rate (a conservative bound). The report shows the rate that applied.

## 13. JGit 7.8.1.202611041320-r on Java 25: CONFIRMED

**Evidence:**
- Maven Central `org.eclipse.jgit` has `<release>` `7.8.1.202611041320-r` (2026-11-04).
- The jar manifest says `Bundle-RequiredExecutionEnvironment: JavaSE-17`, and the class files are major 61. It runs on Java 25.

**Source:** https://repo1.maven.org/maven2/org/eclipse/jgit/org.eclipse.jgit/maven-metadata.xml

**Proposed spine fix:** None.

## 14. ArchUnit 1.5.1 on Java 25 class files: CONFIRMED (tested)

**Evidence:**
- Maven Central `archunit` and `archunit-junit5` have `<release>` 1.5.1 (2025-09-25 metadata update).
- Test: I compiled a record, a sealed interface and a record pattern `switch` with `javac --release 25` (class major **69**). I then imported it with `new ClassFileImporter().importPath(...)` on `archunit-1.5.1.jar` under JDK 25.0.5.1. All 3 classes were imported, with no warnings.

**Source:** https://repo1.maven.org/maven2/com/tngtech/archunit/archunit/maven-metadata.xml, plus the local test.

**Proposed spine fix:** Remove the `[ASSUMPTION]` qualifier on the tool's fitness in AD-1. The choice can stay an assumption; the compatibility is verified.

## 15. RFC 9457 ProblemDetail in Spring Boot 4: CONFIRMED, with a minor RISK

**Evidence:**
- The Boot 4.1 servlet docs say RFC 9457 Problem Details is supported (Spring Framework `ProblemDetail`). It "can be enabled by setting `spring.mvc.problemdetails.enabled` to `true`", so it is opt-in for framework-raised exceptions.
- Custom `ProblemDetail` / `ErrorResponseException` from `@RestControllerAdvice` works regardless of that setting.

**Source:** https://docs.spring.io/spring-boot/4.1/reference/web/servlet.html

**Minor RISK:** RFC 9457 `type` is a URI reference. Bare codes like `unknown-repository` resolve relative to the request URI, which is legal but brittle.

**Proposed spine fix:** In Conventions → Errors (REST): "`spring.mvc.problemdetails.enabled=true`. `type` = `urn:ai-code-review:problem:{code}` (or `/problems/{code}`)."

## 16. Jackson 2 version skew: LangChain4j vs the Boot BOM: RISK

**Evidence:**
- `langchain4j-core-1.24.0.pom` depends on `com.fasterxml.jackson.core:jackson-databind` **2.23.0**.
- Spring Boot 4.1.3's BOM pins `jackson-2-bom.version` to **2.21.8**.
- Under Boot dependency management, LangChain4j is silently **downgraded** two minor versions below what it was built and tested against.

**Sources:**
- https://repo1.maven.org/maven2/dev/langchain4j/langchain4j-core/1.24.0/langchain4j-core-1.24.0.pom
- https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-databind/maven-metadata.xml (latest 2.23.0)

**Proposed spine fix:** In Stack integration notes: "Override `jackson-2-bom.version` to 2.23.0 (the version LangChain4j 1.24.0 requires), or verify with `mvn dependency:tree` plus the LangChain4j fake-model tests at FEATURE-001." Record whichever is chosen.

## 17. Process: the spine records no verification sources: RISK

The Stack table pins exact versions. All of them proved correct, but the spine cites no source or check date for any of them. The Deferred section also says "FEATURE-002 spike confirms" for DeepSeek facts that are already documented publicly:
- the model id
- the thinking default and switch
- tool support
- the error codes
- `json_object` only

**Proposed spine fix:** Add a "Verified (2026-11-21)" column to the Stack table, with a Maven Central or docs URL per row. Fold the confirmed DeepSeek facts (items 9–12) into AD-7 and AD-18, and narrow the FEATURE-002 spike to what docs cannot answer: actual error-body shapes, multi-turn tool behavior with thinking off, and observed latency.

## Minor notes (no change required)

- **Temporal starter:** The Temporal Spring Boot starter 1.41.x now targets Spring Boot 4 (v1.41.0 release notes). The spine's choice of plain SDK beans is still valid. The ADR should note that the starter is now an option, rather than a non-option.
- **Java 25 warnings:** Temporal's shaded gRPC/Netty and Guava may log `sun.misc.Unsafe` deprecation warnings on Java 24+ (JEP 471/498). These are warnings, not failures.
