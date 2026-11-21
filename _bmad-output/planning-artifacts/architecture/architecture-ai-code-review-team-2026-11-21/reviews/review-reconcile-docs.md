# Review: ARCHITECTURE-SPINE.md reconciled against baseline docs

**Date:** 2026-11-21
**Spine:** `../ARCHITECTURE-SPINE.md` (draft)
**Inputs:** `CLAUDE.md`, `docs/architecture.md`, `docs/adr/README.md` (ADR-001..010), `docs/contracts.md`, `docs/implementation-plan.md`, `docs/evaluation.md`, `docs/assumptions-and-risks.md` (baseline 55169b9). PRD `prd.md`/`addendum.md` consulted for context only.

## Verdict

**Approve with fixes.** The spine is faithful to the core doctrine: Temporal is the sole orchestrator, Activities sit at the agent-invocation boundary, tools are read-only and pinned to a snapshot, a Java validator gates findings, there is no application DB, and spend is never claimed as exactly-once. It also resolves most of the inconsistencies listed in the addendum, and says so: unified tool names, a single `reasons` field, a cost budget, `ReportReference` with a manifest, goldset placement and `SINGLE` not plannable.

Gaps remain in four areas:

- **Temporal operational parameters.** Timeouts, heartbeat sizing, the ID-reuse policy and retention are missing or inconsistent.
- **Test infrastructure.** The durability experiments cannot all be run under the spine's testing convention.
- **Dropped contract details.** EvidenceKind, the Category and Severity enums, the coverage shape and the manifest fields are missing.
- **Silent overrides.** Several ADR and contract decisions are overridden without saying so, and the spine does not require the ADR updates that CLAUDE.md rule 10 demands.

Severity: **high** means it blocks a FEATURE or an SM-2 durability scenario, or contradicts itself. **medium** means a requirement was dropped or is ambiguous. **low** means wording, naming or hygiene.

---

## A. Temporal operational semantics

### A1 — Heartbeat timeout is shorter than the LLM call timeout (high, internal contradiction)
- **What:** AD-16 heartbeats "on every LLM turn and tool call" with a **30 s heartbeat timeout** but a **60 s LLM call timeout**. One blocking DeepSeek call of 31–60 s produces no heartbeat. Temporal then times out the attempt and retries it, so a call that succeeded can be re-billed. This is exactly what the critical caveat warns about.
- **Docs:** `contracts.md` §Temporal ("Use heartbeat for long-running cancellable activities"); `architecture.md` failure table, row "Worker dies during specialist" ("Bounded retry; cost and duplicate-call visibility"); `assumptions-and-risks.md` risk "Double billing after Activity restart".
- **Fix (AD-16):** "While an LLM call is blocking, a background ticker heartbeats every 10 s, so the heartbeat timeout (30 s) is independent of the LLM call timeout (60 s). Rule: heartbeat timeout < LLM call timeout is allowed only with the ticker. Otherwise the heartbeat timeout must be ≥ LLM call timeout + 10 s." Also state that cancellation is delivered on the next heartbeat, so cancellation latency is ≤ ticker period + heartbeat round-trip.

### A2 — StartToClose and ScheduleToClose values are missing; the run-timeout "backstop" bypasses the report (high)
- **What:** AD-8 says "A Workflow run timeout is the backstop" but gives no values, and the spine sets no per-Activity `StartToCloseTimeout` or `ScheduleToCloseTimeout`. In Temporal, a run or execution timeout terminates the Workflow without running any Workflow code. So no `validateAndReport` runs. That contradicts AD-13 ("`FAILED`/`CANCELLED` runs get a minimal report") and FR-18. AD-14 also does not say how the Temporal close statuses `TIMED_OUT` and `TERMINATED` map to `ReviewStatus`.
- **Docs:** `contracts.md` line 151 ("Prefer activities with bounded `StartToCloseTimeout` / `ScheduleToCloseTimeout` and explicit retry/non-retry classes"); `architecture.md` failure table, row "Worker dies before Activity starts" ("Correct Temporal timeouts").
- **Fix (new AD-8 sub-table, `[ASSUMPTION]` values):**

  | Activity | StartToClose | ScheduleToClose | Heartbeat |
  | --- | --- | --- | --- |
  | `planNext` | 2 min | 7 min (3 attempts plus backoff) | 30 s |
  | `reviewSpecialist` | slice deadline + 30 s (default 4 min; `SINGLE` 8 min) | min(3 × STC + backoff, remaining elapsed budget) | 30 s |
  | `validateAndReport` | 1 min | 5 min | — |

  - **Primary elapsed-time stop:** a Workflow timer at `maxElapsed` stops scheduling and runs `validateAndReport` with `BUDGET_ELAPSED`.
  - **Backstop:** `WorkflowExecutionTimeout = maxElapsed + 5 min`. If it fires, the run has no report. The API maps describe-status `TIMED_OUT`/`TERMINATED` to `FAILED` with `INTERNAL_ERROR`, and the status response says "no report: hard timeout".

### A3 — Idempotency and status depend on Temporal, but the ID-reuse policy and retention are not set (high)
- **What:** AD-14 relies on "already started → describe → compare hash". Temporal's default `WorkflowIdReusePolicy` (`ALLOW_DUPLICATE`) lets a **closed** `review-{id}` start again. Resubmitting after completion would therefore create a second, billed run instead of returning `200`. Separately, the dev server's namespace retention is finite. After it passes, `describe` returns not-found, so `GET /api/reviews/{id}` reports 404 for a review whose report is still on disk, and the same `reviewId` becomes reusable.
- **Docs:** `contracts.md` §REST ("idempotent conflict semantics"); `architecture.md` §Recovery ("Activity result retention can be affected by configured history/retention") and failure row "API process dies after start" ("Stable ID and conflict handling on retries").
- **Fix (AD-14):**
  - Start with `WorkflowIdReusePolicy.REJECT_DUPLICATE` and `WorkflowIdConflictPolicy.FAIL`.
  - Configure namespace retention explicitly (for example, 30 days) in the dev-server run instructions.
  - When the Workflow is not found, check `output/reviews/{id}/manifest.json`. If it exists, serve status from the manifest and compare its stored request hash, so a request under that `reviewId` still conflicts or deduplicates instead of starting a new run.
  - State plainly that idempotency is guaranteed only within retention **or** while the manifest exists.

### A4 — Per-topic rejection: AD-5 and AD-8 give different reasons (high, internal contradiction)
- **What:** AD-5 treats an over-limit topic as a policy **rejection**. After 3 consecutive rejections the run ends `PARTIAL` with `PLANNER_INVALID`. AD-8 says "exhausting any limit ends the run `PARTIAL` with the matching `BUDGET_*` code", which would be `BUDGET_TOPIC`. The `BUDGET_EXCEEDED` experiment ("Planner repeatedly delegates same topic → PARTIAL with reason") therefore has two possible expected outcomes.
- **Docs:** `evaluation.md` durability row `BUDGET_EXCEEDED`; `assumptions-and-risks.md` risk "Agent repeats delegation indefinitely"; `architecture.md` failure row "Planner loops / delegates repeatedly".
- **Fix (AD-5/AD-8):**
  - A decision that exceeds the per-topic count is rejected with `reasonCode=BUDGET_TOPIC`, and the rejection is fed back to the Planner.
  - If the run ends because of consecutive rejections, the terminal reason is the code of the **last** rejection: `BUDGET_TOPIC` for topic repeats, `PLANNER_INVALID` only for unknown roles or schema failures.
  - Run-wide limits (delegations, tokens, cost, elapsed time) end the run immediately with their `BUDGET_*` code.
  - Add an explicit test-expectation line for `BUDGET_EXCEEDED`.

### A5 — Telemetry for failed attempts: `runId` is missing and the rule cannot be implemented as written (medium)
- **What:** AD-18 says "Every attempt records `AgentUsage`". An attempt that fails or times out never returns its `AgentUsage` to the Workflow, so only logs can carry it. FR-20 still requires "retry is visible in the report". The trace list in `architecture.md` also includes `workflowId/runId`, but the spine's MDC keys are only `reviewId`, `invocationId` and `attempt`.
- **Docs:** `contracts.md` §Pricing ("telemetry should also record attempt IDs"); `architecture.md` §Concurrency (trace IDs: `workflowId/runId`, `agentInvocationId`, Activity attempts); `assumptions-and-risks.md` DoD ("interrupted Activity may retry and that this behavior is recorded").
- **Fix (AD-18):**
  - Every Activity result carries `attempt` from `ActivityInfo.getAttempt()`.
  - The report lists, per invocation, `attempt` and `priorAttemptsUsage: UNKNOWN` when attempt > 1.
  - Per-attempt usage is logged at the end of each LLM turn, with MDC keys `reviewId`, `workflowId`, `runId`, `invocationId` and `attempt`. These logs are the only record of usage from lost attempts.
  - Reword the rule to "every *completed* attempt returns `AgentUsage`; failed attempts are logged and disclosed as unknown".

---

## B. Test infrastructure vs. the durability experiments

### B1 — A "scripted fake `ChatModel`" alone cannot run RATE_LIMIT, WORKER_RESTART or PLANNER_REPLAY (high)
- **What:** The spine's Testing convention: all automated tests use a scripted fake `ChatModel`, Workflow tests use `TestWorkflowEnvironment`, and CI has no Temporal server. Three experiments do not fit:
  - **RATE_LIMIT:** AD-7's 429/5xx classification sits in `adapter.llm` and maps **HTTP or provider exceptions**. A fake `ChatModel` bypasses that code path entirely.
  - **WORKER_RESTART and PLANNER_REPLAY:** they need a real JVM kill against a **persistent** server, plus an LLM-call counter that survives the kill ("no extra planner API call").
  - The **contract** test layer ("mock DeepSeek-compatible chat tool-calling protocol") has been dropped.
- **Docs:** `evaluation.md` §Testing layers (Contract layer; "local persistent service for crash/restart tests") and §Durability experiments; `implementation-plan.md` FEATURE-012.
- **Fix (Testing convention plus a new AD):**
  - (a) **Contract tests:** an HTTP-level OpenAI-compatible stub such as WireMock or MockWebServer `[ASSUMPTION]`, driving the real `adapter.llm` with scripted 200/429/5xx/400 responses and malformed JSON. Runs in CI.
  - (b) **Durability suite,** tagged `durability`, not in CI: a local dev server with `--db-filename`, the app run as a child process, and the HTTP stub run as a **separate** process that counts requests per `invocationId`. The test kills the app process at hook points such as "after `planNext` completed" and asserts the stub's counts.
  - (c) Map each of the 9 experiment rows to a test name and FEATURE in a table, so SM-2 is traceable.

### B2 — The replay-history rule has no start point (medium, FEATURE ordering)
- **What:** AD-17 requires `Workflow.getVersion` for **any** decision-logic change and replay from `src/test/resources/histories/`. No histories exist until a Workflow runs end to end (FEATURE-006). Applied literally from FEATURE-003, every early edit would need version gates.
- **Docs:** `assumptions-and-risks.md` risk "Temporal replay nondeterminism" (control: Workflow replay tests); `contracts.md` §Serialization ("add a replay test").
- **Fix (AD-17):** "`getVersion` gating is required only for changes merged after the first committed history. FEATURE-006 commits the first history (single-agent). FEATURE-010 adds a multi-agent history. Each later Workflow change either adds a `getVersion` branch or regenerates histories in the same PR, with justification."

---

## C. Contract details dropped from the spine

### C1 — `EvidenceKind` (CHANGED_LINE / CONTEXT_LINE) is missing, and so is the rule for out-of-diff citations (medium)
- **What:** Neither AD-11 nor the conventions mention `EvidenceKind`. Nothing says who assigns it, the model or the validator. Nothing says whether a citation in an unchanged file, or in unchanged lines of a changed file, is allowed.
- **Docs:** `contracts.md` enums (`EvidenceKind { CHANGED_LINE, CONTEXT_LINE }`), `CodeEvidence.kind`, report sample `"kind": "CHANGED_LINE"`.
- **Fix (AD-11):**
  - The validator assigns `kind` deterministically: `CHANGED_LINE` if the cited range intersects a HEAD-side hunk of `getDiff`, else `CONTEXT_LINE`.
  - Model-supplied kinds are ignored.
  - `CONTEXT_LINE` evidence is allowed, but a finding needs at least one `CHANGED_LINE` citation to be `SOURCE_VERIFIED` `[ASSUMPTION]`. Without one it is `NEEDS_REVIEW` with the note "context-only evidence".

### C2 — The Category and Severity enums are not fixed, and there is no rule for out-of-enum model values (medium)
- **What:** The dedupe key uses "normalized title/category", but the spine never adopts `Category {CORRECTNESS, SECURITY, PERFORMANCE, CONCURRENCY, DATA_INTEGRITY, RELIABILITY}` or `Severity {CRITICAL, HIGH, MEDIUM, LOW, INFO}`. It also says nothing about a model emitting `"BUG"` or `"MAJOR"`. Severity must be shown as model-assigned (FR-18).
- **Docs:** `contracts.md` domain enums and line 242 ("do not equate model-assigned severity with proven business impact").
- **Fix (Conventions, Serialization row):**
  - Adopt the contract enums verbatim in `domain`.
  - Deserialization is case-insensitive.
  - An unknown category or severity counts as malformed output under AD-6: one repair, then the candidate is rejected with reason `SCHEMA_ENUM`.
  - Reports label the field `modelSeverity`, or render it "severity (model-assigned)".
  - Any role may emit any category.

### C3 — Coverage shape and the "covered" definition (medium)
- **What:** AD-15 computes coverage "from the read ranges that Activities report", but no contract carries read ranges, which `AgentResult` in `contracts.md` lacks. The report coverage shape is not fixed. "100% changed-file coverage" has no definition: is a `getDiff` call enough? Do reads from failed attempts count?
- **Docs:** `contracts.md` report sample `coverage {filesReviewed, filesChanged, completedRoles, incompleteRoles}`; `evaluation.md` Coverage metric ("changed files/context inspected and categories attempted").
- **Fix (AD-15):**
  - `AgentResult` carries `readRanges: List<{path, startLine, endLine, tool}>`.
  - A changed file is covered when a **completed** invocation successfully called `getDiff` on it, or `readFile` over all its HEAD hunks.
  - Report `coverage = {filesChanged, filesReviewed, uncoveredFiles[], completedRoles[], incompleteRoles[]}`.
  - Reads from failed attempts do not count.

### C4 — Manifest content is not listed, so reproducibility inputs are unspecified (medium)
- **What:** AD-13 names `manifest.json`, and AD-18 adds prompt versions and the price table. Nothing else is required. `architecture.md` asks to persist the model ID, fixture ID, review parameters and an input digest. `evaluation.md` asks for "hashes, commit SHAs, model/prompt versions and limitations" and pinned model parameters.
- **Docs:** `architecture.md` §Recovery, last paragraph; `evaluation.md` §Report format and §Protocol step 1; ADR-009 ("include hashes and prompt/model version").
- **Fix (AD-13):** List the manifest fields:
  - `schemaVersion`, `reviewId`, `workflowId`, `runId`
  - `requestHash`, `snapshot`, `mode`, `fixtureId?`
  - `budget`, `model {provider, name, baseUrl host, temperature, maxTokens, thinking:off}`
  - `promptVersions{role→vN}`, `priceTable{date, rates}`
  - `status`, `reasons`, `limitations`, `attempts[]`
  - `files{name→sha256}`

  Also add "pinned model sampling parameters (for example, temperature) come from config and are recorded" to the Config convention.

### C5 — Preflight drops the ancestry and compare policy (medium)
- **What:** AD-3 preflight checks the allowlist, that the SHAs exist, the "uncommitted-only check" and diff size. Nothing covers ancestry, base ≠ head, or an empty diff. "Uncommitted-only" is also undefined for an API that takes two SHAs.
- **Docs:** `contracts.md` line 65 ("validate HEAD/base existence and ancestry/compare policy"); A-07.
- **Fix (AD-3):**
  - Preflight requires full 40-hex SHAs; refs and abbreviations are refused.
  - Both must resolve to commits, and `base ≠ head`.
  - `base` must be an ancestor of `head` `[ASSUMPTION]`. Otherwise reject with problem type `unsupported-comparison`.
  - The diff must be non-empty. Define "uncommitted-only" as "the requested change is not present between the two commits", which surfaces as an empty diff (`empty-diff`).
  - Add both problem types to the REST error list.

### C6 — No REST endpoint table, and report-read behaviour is unspecified (medium)
- **What:** The spine never lists the endpoints. AD-14 says a duplicate returns `200`, but the first-start code (`202` with `Location`) and the behaviour of `GET /report` for `RUNNING`, `FAILED` or `CANCELLED` runs are not stated.
- **Docs:** `contracts.md` §REST (POST 202 + location, GET status, GET /report, POST /cancel; "avoid blocking HTTP").
- **Fix (new Conventions row "API"):**
  - `POST /api/reviews` → `202` + `Location` (new), `200` (same hash), `409` (different hash).
  - `GET /api/reviews/{id}` → status, progress, reasons, coverage, report links.
  - `GET /api/reviews/{id}/report` → `200` for any terminal state that has a report; `409` with type `review-running` while running; `410` with type `report-unavailable` if the file is missing or its sha256 does not match.
  - `POST /api/reviews/{id}/cancel` → `202`, idempotent.

### C7 — Report reconciliation on read is missing (low-medium)
- **What:** AD-13's atomic, deterministic rewrite answers the write half of OQ-4 / Q5. It does not cover the read half: the Workflow has completed but the file is missing or corrupt, for example because the disk was lost.
- **Docs:** `assumptions-and-risks.md` risk "Process restart loses local reports" (control: "atomic write and report reconciliation") and Q5.
- **Fix (AD-13):**
  - The temp file is created in the same directory as the target, so the move is atomic on one filesystem.
  - On read, the API verifies each file's sha256 against the `ReportReference` from the Workflow result. A mismatch or missing file returns `410 report-unavailable`. No regeneration in v1: the Workflow is closed.
  - Document that `output/` must sit on persistent local disk.

### C8 — Pinned Git objects may be garbage-collected (medium)
- **What:** AD-9 reads blobs by commit, but nothing keeps base and head reachable for the length of a run, so a `git gc` could prune them. `validateAndReport` reads HEAD again after the specialists, so it is exposed to the full run duration. The spine also says nothing about what happens when an object is missing mid-run.
- **Docs:** `assumptions-and-risks.md` risk "Git garbage collection removes pinned objects"; `architecture.md` failure row "Git repository changes" ("Git objects must remain locally available until run completes").
- **Fix (AD-19 + AD-9):**
  - The fixture build script tags base and head (`refs/tags/fixture/{name}/base|head`), so both are always reachable.
  - Preflight rejects SHAs not reachable from any ref (`unreachable-sha`).
  - A `MissingObjectException` during a run is a non-retryable `ApplicationFailure` in tools: the tool returns an error string. In the validator, the candidate becomes `INVALID_EVIDENCE` with the note "object unavailable". The run never crashes over a missing object.
  - The app never runs gc. Writing tags is the fixture script's job, not the agent's.

### C9 — Secret redaction conflicts with exact-quote validation (medium)
- **What:** `ContentPolicy` redacts secrets before the model sees them, so the model quotes the redacted text. AD-11 compares that quote with raw HEAD. Any finding on a line holding a secret, which is exactly the "sensitive data in debug logs" seeded defect, becomes `NEEDS_REVIEW` or `INVALID_EVIDENCE`. The report could also print unredacted snippets.
- **Docs:** `contracts.md` tool contract ("redaction of secrets"); `evaluation.md` seeded defect 5; `assumptions-and-risks.md` "Leaked tokens or code in traces".
- **Fix (AD-11):** "The validator matches quotes against the **same ContentPolicy-redacted view** the tools served. Report snippets are always taken from the redacted view."

### C10 — Token budget defaults and Planner cost accounting are missing (low-medium)
- **What:** `BUDGET_TOKENS` exists, but the AD-8 defaults table has no token limits, although `ReviewBudget` in `contracts.md` has `maxEstimatedInputTokens/OutputTokens`. AD-8 slices only `AgentTask`. Nothing caps `planNext` cost, or says whether it counts toward the run's cost and tokens.
- **Docs:** `contracts.md` `ReviewBudget`; `evaluation.md` protocol step 3 ("comparable total token/spend budget, not identical per-agent budgets").
- **Fix (AD-8):**
  - Add defaults for input and output tokens per run.
  - Add a Planner slice (for example, ≤ USD 0.02 and ≤ 2 000 output tokens per call), counted toward the run totals.
  - State that the multi-agent vs single-agent comparability unit is the **run cost ceiling** (USD 0.50 for both). Tool-call totals differ (6 × 12 vs 30); record that as a known asymmetry.

### C11 — LLM text goes into the Markdown report and the ledger without being treated as untrusted (medium)
- **What:** CLAUDE.md rule 8 says all LLM outputs are untrusted. AD-12's ledger is "built deterministically from prior results", which may include model-written `summarizedWork`. That gives a prompt-injection relay path from one agent to the next. `report.md` renders model text, so raw HTML or links could get in.
- **Docs:** CLAUDE.md rule 8; `architecture.md` §Concurrency ("Source comments and diffs can carry prompt injection"); `assumptions-and-risks.md` risk "Prompt injection".
- **Fix (AD-12 + AD-13):**
  - The ledger contains only structured fields (role, topic, file:line refs, candidate titles), with each free-text field truncated to 300 chars and wrapped in an "untrusted model output" delimiter in prompts.
  - The Markdown writer escapes HTML and link syntax in every model-originated string, or places it in code spans.

---

## D. Silent overrides of ADRs and contracts, and the CLAUDE.md rule 10 obligation

### D1 — The Activity-set redesign overrides ADR-004 and `contracts.md` without saying so (medium)
- **What:** AD-3 collapses `reviewCorrectness/Security/Performance/Single` into one `reviewSpecialist(AgentTask)`. It changes `planNext → PlanDecision` into `→ PlanOutcome`, and renames `validateAndAssemble` to `validateAndReport`. ADR-004 names per-role Activities. The change is defensible, but the spine does not label it as an override. One side effect is unaddressed: per-role Activity options become harder, and `SINGLE` needs longer timeouts (see A2). The Temporal UI also shows one Activity type for all roles.
- **Docs:** ADR-004 Decision; `contracts.md` §Temporal Java Interfaces; `architecture.md` loop pseudocode.
- **Fix (AD-3):** Add "**Overrides** ADR-004 naming and the `contracts.md` Activity interfaces." Then: "The Workflow builds `ActivityOptions` per role (`SINGLE` uses longer STC). Each scheduled Activity gets a summary `role/topic` for Temporal UI visibility. `PlanOutcome = Decision(PlanDecision) | Invalid(reasonCode)`."

### D2 — Goldset and fixture paths silently replace `evaluation.md` (low-medium)
- **What:** AD-10 and the seed tree use `evaluation/goldset/` and `fixtures/{name}/`. `evaluation.md` specifies `samples/order-processing-service` and `samples/goldset.json`. The spine's choice resolves addendum B12, but it does not say it supersedes those paths. It also does not say where the **generated** Git repos live, which the allowlist must point to.
- **Docs:** `evaluation.md` §Synthetic ground-truth repository.
- **Fix (AD-10/AD-19):** "Supersedes the `samples/` paths in `evaluation.md`. Fixture sources go in `fixtures/{name}/src`, the generated repo in `build/fixture-repos/{name}/` (gitignored), and the allowlist points at the generated repo. The goldset stays at `evaluation/goldset/{name}.json`."

### D3 — The spine does not require the ADR and doc updates its decisions imply (medium, CLAUDE.md rule 10)
- **What:** CLAUDE.md rule 10 and the implementation-plan quality bar ("Every new nontrivial assumption or divergence must update ADRs/risks") require docs to follow decisions. The spine makes the following changes, and no FEATURE owns updating ADR-004, ADR-009, `contracts.md` or `evaluation.md` to match:
  - the single `reviewSpecialist`
  - `PlanOutcome`
  - `reasons` replacing `completionReasons`
  - `ReportReference` with a manifest and a sha256 per file
  - the cost budget
  - the relocated goldset
  - `RejectedDecision`
  - `rejectedCandidates`
  - the Temporal plain-SDK choice
  - no `langchain4j-agentic`
- **Fix (new Deferred/obligation line):** "FEATURE-001 (or a docs-only precursor) updates ADR-004 and ADR-009, the `contracts.md` records and interfaces, and the `evaluation.md` paths to match this spine. ADR-011 records 'single parameterized specialist Activity', and ADR-012 records 'Temporal plain SDK, no Spring starter; no langchain4j-agentic'."

### D4 — Version pinning: Temporal CLI is "latest" and other versions are not verified (low-medium, CLAUDE.md rule 9)
- **What:** The Stack table pins library versions, but the "Temporal CLI dev server: latest". That contradicts the risk control "Pin via BOM and lock documented compatibility matrix". No verification date or source is given for the other versions, so I could not confirm them.
- **Docs:** CLAUDE.md rule 9; `assumptions-and-risks.md` risk "Integration framework versions drift" and Q1.
- **Fix (Stack):** Pin the Temporal CLI to an exact version. Add the line "Versions verified against Maven Central on <date>; FEATURE-001 must compile and run `mvn verify` on Temurin 25 with exactly these versions before they are considered resolved." Mark `deepseek-flash` as unverified until FEATURE-002.

### D5 — Global code-quality rules do not appear in the conventions (low)
- **What:** The user's global rules apply to every FEATURE: no duplicated string literals (use constants), cognitive complexity ≤ 15, Kotlin and Java naming rules, and a version bump in both the content and the filename of any OpenAPI or AsyncAPI file. The spine defers the "formatter/linter choice" and does not record these rules as constraints. The Workflow loop is the place most likely to exceed complexity 15.
- **Fix (Conventions):** Add a "Code quality" row:
  - Sonar rules: cognitive complexity ≤ 15, so the Workflow loop is split into small private steps. No duplicated literals: reason codes and problem types live in enums or constants.
  - If an OpenAPI spec is published, it is `openapi-v{n}.yaml`, with `info.version` bumped on every change.

---

## E. Implementation-plan FEATURE impact

The spine does not **block** any FEATURE. It does **move work** between FEATUREs in ways the plan does not reflect:

| # | Spine rule | Plan impact | Proposed fix |
| --- | --- | --- | --- |
| E1 | AD-4: `SINGLE_AGENT` = role `SINGLE` (all categories) | FEATURE-006 is "Correctness review". No FEATURE owns `SINGLE` (addendum B4) | State in Capability Map: FEATURE-006 delivers `SINGLE_AGENT` mode with role `SINGLE`, and the Correctness prompt follows in FEATURE-007/008. Or FEATURE-006 ships both `SINGLE` and `CORRECTNESS` prompts. Either way, name the owner. |
| E2 | AD-11/AD-13: validation runs **only** inside `validateAndReport`, which also writes the reports | FEATURE-006 needs validation, so a minimal JSON report writer moves from FEATURE-011 into FEATURE-006. FEATURE-011 keeps Markdown, manifest, API and idempotency | Add to Capability Map: "FEATURE-006: `validateAndReport` with `report.json`; FEATURE-011: `report.md`, `manifest.json`, REST, AD-14". |
| E3 | AD-4/AD-8: `SINGLE` runs under `ReviewBudget` and the slice | Budget enforcement (slice counters, cost cap) is needed in FEATURE-006, not first in FEATURE-007 "Planner and Budgets" | FEATURE-006 owns `ReviewBudget` and slice enforcement in the Activity. FEATURE-007 adds the delegation and topic policy. |
| E4 | AD-19: tiny fixture "built the same way" | The plan has no fixture before FEATURE-013, but FEATURE-004/005/006 need one (OQ-7) | Assign the fixture build script and the tiny fixture to FEATURE-004. FEATURE-013 adds the seeded fixture and goldset. |
| E5 | AD-17: replay histories | Plan places replay in FEATURE-012 | See B2: first history in FEATURE-006, multi-agent history in FEATURE-010. |
| E6 | AD-1: ArchUnit; Conventions: problem+json, config tree | FEATURE-001 scope grows | Fine. List these in FEATURE-001 acceptance criteria. |
| E7 | Plan's dependency table vs diagram mismatch (011, 012, 014; addendum B10) | Not resolved by the spine | Low. Recommend the table as authoritative (011←005,010; 012←003,010,011; 014←011,012,013) and fix the diagram during the docs update in D3. |
| E8 | B1 durability suite | FEATURE-012 needs the HTTP stub and the process-kill harness; FEATURE-002 should build the HTTP stub for contract tests | FEATURE-002 delivers the HTTP-level stub. FEATURE-012 delivers the durability harness. |

---

## F. Coverage check: failure-scenario table (`architecture.md`)

| Row | Spine coverage | Gap |
| --- | --- | --- |
| API dies after start | AD-14 | A3 (reuse policy, retention) |
| Worker dies before Activity starts | partial | A2 (timeouts) |
| Worker dies after specialist result persisted | AD-2/AD-3 | — |
| Worker dies during specialist | AD-7, AD-8 | A1, A5 (attempt visibility) |
| DeepSeek 429/5xx | AD-7 | B1 (testability) |
| Invalid schema / hallucinated lines | AD-6, AD-11 | C1, C2 |
| Planner loops | AD-5, AD-8 | A4 |
| Temporal data lost | Stack/Env (`--db-filename`) | Low: state "durability void if `temporal.db` is lost" (NFR-2) in Environment |
| Artifact written, ack lost | AD-13 | C7 |
| Git repository changes | AD-9 | C8 (GC, missing objects) |

## G. Coverage check: durability experiments (`evaluation.md`)

| Test | Spine rule | Runnable under spine's test convention? |
| --- | --- | --- |
| WORKER_RESTART | AD-2/3 | No. Needs B1(b) |
| PLANNER_REPLAY | AD-2 | No. Needs B1(b) and an external call counter |
| DOUBLE_WRITE | AD-13 | Yes (unit plus Activity test) |
| RATE_LIMIT | AD-7 | No. Fake `ChatModel` bypasses classification; needs B1(a) |
| BAD_SCHEMA | AD-6 | Yes |
| FAKE_LINE | AD-11 | Yes |
| BUDGET_EXCEEDED | AD-5/AD-8 | Yes, but the expected reason is ambiguous (A4) |
| CANCEL | AD-16 | Partially: needs A1 for bounded latency |
| PROMPT_INJECTION | AD-9 | Yes; also C11 |

## H. Items checked and found consistent (no action)

- ADR-001: one JVM, logical packages (spine refines them into hexagonal layers).
- ADR-003: no supervisor; Planner as an Activity.
- ADR-005: read-only, pinned.
- ADR-006: candidates plus validator; `SOURCE_VERIFIED` is not proof.
- ADR-007: baseline with same model, tools and rules.
- ADR-008: persistent dev server.
- ADR-010: Java-enforced limits.
- CLAUDE.md rules 2–7: no application DB, no exactly-once claim.
- AD-9's unified tool names, AD-15's single `reasons` field and AD-8's cost budget are explicit, intentional overrides.
- Non-goals are carried through in Deferred.
