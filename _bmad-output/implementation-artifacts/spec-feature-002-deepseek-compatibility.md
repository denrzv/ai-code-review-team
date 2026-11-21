---
title: 'FEATURE-002 · Verify the DeepSeek review protocol'
type: 'feature'
created: '2026-11-21'
status: 'done'
baseline_commit: fc89ed0409226aa34ab7d1ddfd78183796596fb9
route: 'dispatch'
review_loop_iteration: 0
context: ['{project-root}/CLAUDE.md']
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem.** GitHub issue #2 and Story 1.2 require evidence that DeepSeek Flash supports the request and response protocol needed by the review service. FEATURE-001 resolved the dependencies and merged in PR #15.

**Approach.** Build a LangChain4j adapter with a deterministic HTTP contract suite and an opt-in live test that sends a fixed synthetic fixture. Exercise Planner JSON and a reviewer turn with two sequential local tool calls.

## Boundaries & Constraints

**Always.** Read model and base URL from review settings and the key from the environment. Default to `deepseek-flash`. Admit only bounded synthetic inputs and local read-only fixture tools. Enforce the request, tool, token, estimated cost and elapsed allowances in Java within the smoke invocation. Keep provider built-in tools and automatic retries disabled. Return sanitized classifications and usage without keys, prompts, or response bodies.

**Never.** Introduce Workflow or Activity wiring, real repository tools, Planner delegation policy, or new review endpoints. Temporal owns orchestration and future Activity retries.

## I/O & Edge-Case Matrix

| Given | When | Then |
| --- | --- | --- |
| Planner request with budget | Adapter sends a completion | Wire includes the configured model, `thinking.type=disabled`, `max_tokens` and `response_format.type=json_object` |
| Reviewer turn and two scripted calls to a fixture tool | Adapter handles the calls in sequence | Each tool result is tied to its call ID and sent in history. Tool name, arguments and allowance are checked before execution |
| Blank, truncated, malformed or schema-invalid Planner output | Adapter parses it | At most one repair occurs when affordable, then a valid typed result or a typed invalid outcome. Policy refusal bypasses repair |
| HTTP 400/401 or policy refusal, HTTP 429 or transient 5xx, or a transient transport failure | Adapter classifies the result | Invalid/auth/policy is permanent. Rate limits and transient failures are retryable. Each attempted request is sent once by the client and transport |
| Usage or usage details are absent | Adapter reports the attempt | Preserve unknown values, and label cost as an estimate based on configured dated prices and available fields |
| Non-synthetic input or live test lacking opt-in or credentials | Adapter or live test starts | No provider request occurs |

</frozen-after-approval>

## Code Map

- `pom.xml` resolves `langchain4j-open-ai` 1.24.0 and Jackson 2.23.0. `OpenAiChatModel` accepts custom top-level parameters and an explicit retry count. Chat requests can set output limits and object JSON mode. Tool-result messages preserve call IDs.
- The resolved default JDK HTTP transport can retry connection failures. Supply an explicit transport with retries and redirects disabled through LangChain4j's HTTP client builder. Verify the behavior by counting requests at the stub.
- `ReviewProperties.java` and `application.yaml` already supply endpoint, model, limits and dated prices. Pass selected values at the composition boundary to respect `ArchitectureTest.java`.
- Story 1.2 and Architecture Spine AD-6, AD-7, AD-8 and AD-18 define local validation, retry policy, budget checks and privacy.

## Tasks & Acceptance

**Execution**

- [x] `src/main/java/dev/denrzv/codereview/adapter/llm/DeepSeekAdapter.java` adds the transport and response classification. Disable both retry layers and request/response logging. Keep exception handling free of provider payloads.
- [x] `src/main/java/dev/denrzv/codereview/adapter/llm/DeepSeekProtocolSmoke.java` adds the bounded synthetic turn, fixture tool and Planner-shaped JSON validation. Reserve allowance before each call, including repair, with history included in the input estimate. Capture observed usage separately from estimated charge.
- [x] `src/test/java/dev/denrzv/codereview/adapter/llm/DeepSeekProtocolTest.java` asserts the matrix against a loopback HTTP stub. Count actual requests, including on early disconnect.
- [x] `src/test/java/dev/denrzv/codereview/adapter/llm/DeepSeekLiveSmokeTest.java` exercises the same path when explicitly enabled with environment credentials. Report only model, outcome, counts, latency and usage estimates.
- [x] `docs/compatibility.md` and `docs/adr/README.md` record the wire fields, failure mapping, verification commands and observed limits of the provider evidence.

**Acceptance criteria**

- Given Temurin 25 and no provider key, when the operator or CI runs `./mvnw -B clean verify`, then the loopback protocol and existing verification suite pass.
- Given explicit opt-in and provider credentials, when the operator runs the live smoke, then it checks the documented fields against DeepSeek using the fixed synthetic fixture and emits only sanitized telemetry.

## Implementation Notes

- The wire suite verifies the planner fields, ordered tool results and pre-dispatch guards, planner repair and refusal, typed provider/transport errors with request counts, usage absent or partial, and the live opt-in and credential gate through loopback. Final Java 25 clean verification passed 76 tests, including 49 loopback protocol cases. The opt-in live test was skipped, so live provider behavior remains unverified. Review fixes were verified locally and no findings were deferred.

## Spec Change Log

## Review Triage Log

| Finding | Verdict | Route and evidence |
| --- | --- | --- |
| Blind · raw adapter can bypass fixture policy | false | The adapter is package-private and its only caller constructs requests after checking the fixed fixture ID. A new package caller would need its own policy before sending. |
| Blind · invalid response can trigger repair after usage exceeds reservation | high | Patch. Usage is captured before SDK envelope decoding and the old guard ran only for successful replies. |
| Blind · partial usage can bypass token limits | high | Patch. Cache or total counts can show an overage while individual counters are missing. |
| Blind · repair dispatch and repair budget denial conflated | medium | Patch. The original result should survive only when no repair was dispatched. |
| Blind · run tool limit omitted from live composition | false | The settings constructor requires the run limit to cover the specialist limit, and live composition clamps to the latter. |
| Blind · live success can accept unexpected Planner delegation | medium | Patch. Verify the fixture's requested COMPLETE action in the live assertion. |
| Blind · enabled live test skips with no key | medium | Patch. A skipped test under explicit opt-in can be mistaken for provider verification. |
| Blind · live test disregards reported reasoning usage | medium | Patch. Positive observed reasoning contradicts the requested non-thinking run. Keep missing telemetry unknown. |
| Blind · stub does not verify exact method and path | medium | Patch. The server context accepts prefixes and its handler accepted any method. |
| Blind · phase timeouts shorter than documented deadline | medium | Patch. The transport previously retained its default timeouts. |
| Edge · DNS may outlast total caller timeout | medium | Patch. A synchronous system lookup can outlive cancellation. Bound the caller wait and document DNS cancellation limits. |
| Edge · SDK can silently drop a malformed extra tool call | medium | Patch. Check each raw tool-call type before SDK conversion. |
| Edge · invalid response usage can allow an extra paid request | high | Patch. Same root cause as the malformed-response repair overage. |
| Edge · cache counts can exceed input reservation | high | Patch. Same root cause as the partial-usage overage. |
| Edge · live success can conceal positive reasoning usage | medium | Patch. Same root cause as the missing live reasoning assertion. |
| Verification · explicit refusal field lacks test coverage | medium | Patch. Removing the branch left the suite green and could allow Planner repair on refusal. |
| Verification · reviewer can appear to finish before second tool result | medium | Patch. Removing the guard left the suite green. |
| Verification · repeated tool call ID lacks a regression case | medium | Patch. Removing the check left the suite green and permits ambiguous result correlation. |
| Verification · tool schema only checks enum length | medium | Patch. Substituting a different value left the suite green. |
| Verification · actual JUnit method can bypass explicit opt-in | medium | Patch. The suite previously exercised a helper rather than the JUnit gate. |

## Verification

- `./mvnw -B -Dtest=DeepSeekProtocolTest test` checks the protocol matrix.
- `./mvnw -B clean verify` checks the full Java 25 suite without a key.
- `./mvnw -B -Dtest=DeepSeekLiveSmokeTest -Ddeepseek.live=true test` requires `DEEPSEEK_API_KEY` in the environment and performs bounded, billable calls.
