# Adversarial Review — Architecture Spine (AI Code Review Team)

- **Target:** `ARCHITECTURE-SPINE.md` (status: draft, 2026-11-21)
- **Context read:** PRD `prd-ai-code-review-team-2026-11-21/prd.md`, `docs/contracts.md`, `docs/implementation-plan.md`, `docs/evaluation.md`, `docs/architecture.md`
- **Lens:** For each pair of features, assume two agents each obey every AD exactly, then look for the place where their code still won't fit together. Each such pair is a hole, and each hole comes with proposed AD Rule text.
- **Reviewer stance:** read-only. This file is the only output.

## Verdict

**Not build-safe yet. Rework it before FEATURE-005/006/007 begin.** The spine is strong on *process* invariants: one Workflow, no I/O in the Workflow body, one retry layer, single writer, a fixed Activity set. It is weak on *data* invariants. Money, tokens, coverage, attempts, topic keys, report schema and `ReportInput` each have either no owner, two plausible owners, or an undefined unit. `docs/contracts.md` disagrees with the spine in about 10 places, and the spine never says which document wins. `CLAUDE.md` tells every implementing agent to read `contracts.md` first. So a builder can follow both documents faithfully and still produce incompatible units.

I found 16 holes. 7 are blocking: they would cause an integration failure or a wrong experiment result. 9 are significant: they would cause rework or silent metric skew.

| # | Severity | Pair | Hole |
|---|---|---|---|
| H1 | Blocking | any feature vs `contracts.md` | No precedence rule between spine and `docs/contracts.md`; ~10 direct contradictions |
| H2 | Blocking | F006/F007 vs F010 vs F011 | Usage/cost aggregation: no owner, no unit, failed attempts invisible to Workflow |
| H3 | Blocking | F006 single agent vs F010/F012 | Retries can blow the run cost ceiling (SINGLE: 3 × 0.50 USD) |
| H4 | Blocking | F006 vs F010 | Slice exhaustion: invocation-normal or run-ending `BUDGET_*`? |
| H5 | Blocking | F004/F006 vs F011 vs F014 | Coverage: no read-range shape, no definition, changed-file list has no path into the Workflow |
| H6 | Blocking | F007 policy vs F010 loop | Topic/delegation budget hit: `RejectedDecision` (counts toward `PLANNER_INVALID`) or `BUDGET_TOPIC` end? |
| H7 | Blocking | F003/F007 vs F011 vs F014 | Serialization: sealed `PlanOutcome` needs Jackson annotations (banned in `domain`); Spring Boot 4 = Jackson 3 vs Temporal = Jackson 2 |
| H8 | Significant | F010 vs F011 | `ReportInput`: who builds it, what's in it, who decides final status and "no issues found" |
| H9 | Significant | F005 vs F011/F014 | `ReviewFinding.reviewer` is singular but dedupe "keeps every provenance"; category in dedupe key double-counts cross-role findings |
| H10 | Significant | F004 vs F005 | Validator vs `ContentPolicy`: redacted view vs raw blob, 200-line cap on validation reads |
| H11 | Significant | F006 adapter vs F005 validator | Caps (≤ 20 candidates, ≤ 15-line quotes): truncate, drop or reject, and who does it |
| H12 | Significant | F011 API vs F012 tests | Canonical payload hash undefined; budget snapshot timing (config read in Workflow = non-determinism) |
| H13 | Significant | F007 vs F010 | Planner has no slice/cost cap; Planner cost vs comparable-budget fairness with SINGLE |
| H14 | Significant | F004 vs F001 preflight vs F014 | Units: "changed lines", "diff lines", "chars" undefined; truncated read semantics |
| H15 | Significant | F013 vs F014 | Goldset schema and location-match tolerance have no owner; generated fixture repo location undefined; AD-19 vs seed diagram (eval → REST) |
| H16 | Significant | F007 vs F010 | Ledger summary: owner, format, versioning, untrusted text forwarded to Planner |

---

## H1 — The spine and `docs/contracts.md` contradict each other, with no precedence rule (Blocking)

**Pair:** FEATURE-004 (reads `contracts.md` as instructed by `CLAUDE.md`) vs FEATURE-006 (reads spine AD-9).

Both documents are "binding" from the implementer's view. The contradictions:

| Concern | `docs/contracts.md` | Spine |
|---|---|---|
| Planner Activity | `PlanDecision planNext(...)` in `PlanActivities` | `planNext → PlanOutcome` (AD-3, AD-6) |
| Specialist Activity | 4 methods `reviewCorrectness/Security/Performance/Single` | one `reviewSpecialist(AgentTask)` (AD-3) |
| Report Activity | `validateAndAssemble` | `validateAndReport` |
| Tool names | `listRelatedTests`, `readBuildMetadata` (port) / `getBuildMetadata` (tool) | `findRelatedTests`, `getBuildMetadata` = port names (AD-9) |
| `ReportReference` | one `sha256`, no manifest | `manifestPath` + sha256 per file (AD-13) |
| Reasons | `List<String> completionReasons` | `List<TerminalReason{code, detail}> reasons` (AD-15) |
| `ReviewBudget` | tokens only, no cost | includes `maxEstimatedCostUsd` (AD-8) |
| `AgentTask` | `maxToolCalls` only | full slice: tool calls, output tokens, cost cap, deadline (AD-8) |
| `AgentUsage` | no cost, no attempt | cost + attempt number (AD-18) |
| Report | no `rejectedCandidates`, coverage `{filesReviewed, filesChanged, ...}` | `rejectedCandidates` required (AD-11) |
| Interface naming | `PlanActivities`/`SpecialistActivities`/`ReportActivities` | "Activity interfaces end in `Activities`": consistent, but the grouping differs |

A FEATURE-004 agent that implements `readBuildMetadata` + `listRelatedTests` obeys `contracts.md`. A FEATURE-006 agent that binds tools named `getBuildMetadata`/`findRelatedTests` obeys AD-9. Each is correct by its own document, and the two units don't fit.

**Proposed new AD-0 — Spine precedence**

> **Rule:** Where this spine and any file under `docs/` disagree, the spine wins. FEATURE-001 must either update `docs/contracts.md` to match the spine (Activity signatures, tool names, `ReportReference`, `TerminalReason`, `ReviewBudget`, `AgentTask` slice, `AgentUsage`) or add a banner at its top: "Superseded by ARCHITECTURE-SPINE.md where they differ". `CLAUDE.md`'s read-first list must name the spine before `contracts.md`. No feature may implement a name or shape from `contracts.md` that contradicts an AD.

---

## H2 — Usage and cost aggregation has no owner and no unit (Blocking)

**Pairs:**
- FEATURE-006 (specialist adapter) vs FEATURE-011 (reports)
- FEATURE-007 (Planner) vs FEATURE-010 (Workflow budget check)

What AD-18 says: "every attempt records `AgentUsage` ... estimated cost from the configured price table."

What AD-8 says: the Workflow checks remaining cost. But:

1. **Failed attempts never reach the Workflow.** An Activity attempt that throws (retryable 5xx after two paid tool-loop calls, a worker kill) returns nothing, so its `AgentUsage` exists only in logs. "Every attempt records" cannot mean "every attempt's usage is in `AgentResult`". F006 could reasonably sum usage across its own repair request and loop. F011 could reasonably sum `AgentResult.usage` across results. Neither sees the lost attempts, and neither is wrong under the ADs.
2. **No attempt field travels with results.** FR-19 requires "retried attempts are counted and disclosed". The Workflow can't learn the attempt count unless the Activity puts `Activity.getExecutionContext().getInfo().getAttempt()` into its result. No AD requires that.
3. **Unit and precision.** F006 could produce `double estimatedCostUsd`, F010 could compare it with `BigDecimal maxEstimatedCostUsd`, and F011 could print `0.30000000000000004`. That breaks AD-13's byte-identical rewrite whenever summation order differs, for example after merging Planner and specialist usage.
4. **Planner usage.** AD-3's `PlanOutcome` has no defined usage field. If F007 omits it, multi-agent cost is under-reported. That biases the central experiment (SM-1) in favour of multi-agent.
5. **Provider-reported vs estimated tokens.** FR-19 says actual provider usage is shown where available and estimates are labelled. No AD gives the field that carries the label.

**Proposed tightened AD-18 (usage clause) plus new AD-20 — Usage ledger**

> **AD-18 add:** `AgentUsage` is a `domain` record: `{provider, model, promptVersion, attempt:int, inputTokens:long, outputTokens:long, llmRequests:int, toolCalls:int, costMicroUsd:long, source: PROVIDER|ESTIMATED}`. Cost is an integer in **micro-USD**, computed by a pure `domain.PriceTable.costMicroUsd(model, inputTokens, outputTokens)` with half-up rounding per request. No `double` for money anywhere. `PlanOutcome` and `AgentResult` each carry exactly one `AgentUsage` for the *successful attempt*, summed over all LLM requests in that attempt, the repair request included. `attempt` is read from the Temporal Activity context.
>
> **AD-20 — The Workflow owns the usage ledger.** Only the Workflow aggregates usage. It appends each returned `AgentUsage` to a `UsageLedger` in Workflow state, keyed by `invocationId`. A returned `attempt > 1` adds `attempt − 1` to `unreportedAttempts` for that invocation. The report's totals come from the ledger, never recomputed by `validateAndReport` or `evaluation`. The report shows `reportedCostMicroUsd`, `unreportedAttempts` and `costUpperBoundMicroUsd` (reported + Σ over `unreportedAttempts` × that invocation's cost cap). The JSON renders money as a decimal string with 6 fractional digits.

---

## H3 — Retries can push a run past its cost ceiling (Blocking; violates NFR-5 / SM-5)

**Pair:** FEATURE-006 single agent vs FEATURE-012 (rate-limit and worker-kill scenarios).

Single-agent defaults: cost cap per invocation is USD 0.50, which equals the run cost of USD 0.50. AD-7 allows 3 attempts. The Activity enforces the slice "with Java counters", and those counters reset on each attempt. So attempts 1 and 2 can each spend up to 0.50 before a 5xx or crash, and attempt 3 spends another 0.50. Actual spend is up to USD 1.50 on a run whose ceiling is USD 0.50. The same applies to every multi-agent invocation (0.10 × 3). Every AD is obeyed, yet SM-5 ("no run exceeds its cost ceiling") fails, and F012's "budget exceeded" scenario cannot be asserted truthfully.

**Proposed tightened AD-8 (slice clause)**

> **Rule add:** The slice is per **invocation across all its attempts**, not per attempt. After every LLM response and tool call, the Activity heartbeats its cumulative `AgentUsage` (and read ranges, H5) as heartbeat details. On `attempt > 1` it restores its counters from `getHeartbeatDetails()` before making any call. Spend after the last heartbeat is unknown and is disclosed through AD-20's upper bound. Before each LLM request, the Activity stops if `spent + worstCase(nextRequest) > costCap`, where `worstCase` = (current prompt tokens × input price) + (`maxOutputTokens` × output price). Before scheduling, the Workflow requires `remainingRunCost ≥ costCap`, and it charges the *upper bound* (AD-20), not the reported cost, against the run budget. `SINGLE`'s cost cap is `min(configured, runCost − plannerReserve)`, where `plannerReserve = 0` in SINGLE mode.

---

## H4 — Is per-invocation slice exhaustion normal, or does it end the run? (Blocking)

**Pair:** FEATURE-006 (specialist stops at 12 tool calls and returns its candidates) vs FEATURE-010 (Workflow).

AD-8 says "Exhausting **any** limit ends the run `PARTIAL` with the matching `BUDGET_*` code", and `BUDGET_TOOL_CALLS` exists in AD-15. Two readings follow:
- F010 reads "any limit" to include the per-invocation tool-call slice. Then any specialist that uses its 12 calls ends the whole multi-agent run `PARTIAL / BUDGET_TOOL_CALLS`, and the Planner never gets a second turn.
- F006 treats reaching the slice as a normal stop and returns `AgentResult` with no marker at all, so F010 cannot even tell it happened.

The two readings produce opposite run statuses for identical behaviour. They also skew the experiment's "partial run count" metric between the modes.

**Proposed tightened AD-8 / AD-15**

> **Rule:** `AgentResult` carries `stopReason ∈ {MODEL_DONE, SLICE_TOOL_CALLS, SLICE_OUTPUT_TOKENS, SLICE_COST, SLICE_DEADLINE, CANCELLED}`. Slice exhaustion is **not** a run-terminal event in `MULTI_AGENT`. The Workflow records it in the decision trail, marks that role incomplete for coverage and asks the Planner again. Run-terminal `BUDGET_*` codes come only from run-level limits: delegations, per-topic count, run tokens, run cost and elapsed time. `BUDGET_TOOL_CALLS` is used only in `SINGLE_AGENT`, where any slice stop other than `MODEL_DONE` ends the run `PARTIAL` with the matching `BUDGET_*` code (`SLICE_COST→BUDGET_COST`, `SLICE_DEADLINE→BUDGET_ELAPSED`, `SLICE_OUTPUT_TOKENS→BUDGET_TOKENS`, `SLICE_TOOL_CALLS→BUDGET_TOOL_CALLS`).

---

## H5 — Coverage has no data shape, no definition and no input path (Blocking)

**Pairs:**
- FEATURE-004 tools / FEATURE-006 agent vs FEATURE-011 reports
- FEATURE-011 vs FEATURE-014 (coverage metric)

AD-15 says "Coverage is computed in `domain` from the read ranges that Activities report." What's missing:

1. **No field.** `AgentResult` in contracts has no read ranges, and the spine doesn't add one. F006 might report `filesRead: List<String>`, a later feature might report `List<ReadRange>`.
2. **No source of truth.** Ranges could be model-claimed or tool-observed. If they come from the model, they are untrusted output (CLAUDE.md §8), and coverage could be faked.
3. **No denominator in the Workflow.** The Workflow can't call `listChangedFiles` (AD-2). The changed-file list is known only to API preflight (AD-3). Nothing carries it into `ReviewCommand`, so F010 has no denominator unless it adds an Activity, which AD-3 forbids.
4. **No definition.** F011 could count a file as covered when any tool touched it. F014 could count changed *lines* covered. A `getDiff` that was truncated at 400 lines might count as full coverage under one reading. "100% changed-file coverage" (AD-15) is then measured differently by the report and by the evaluation.
5. **Failed or cancelled invocations.** Reads done before a crash or cancel are lost unless they are heartbeated (H3).
6. **History bound.** Read ranges add to history. AD-12 has no cap for them.

**Proposed new AD-21 — Coverage**

> **Rule:** `ReviewCommand` carries `changedFiles: List<ChangedFileSummary{path, changeType, changedHeadRanges: List<LineRange>}>`, produced once by API preflight through `RepositoryReadPort.listChangedFiles` and frozen into Workflow input. The **tool wrapper**, never the model, records `ReadRange{path, startLine, endLine, via: DIFF|FILE}` (HEAD line numbers, merged, ≤ 200 entries per invocation) for each successful, non-refused, non-truncated result. Ranges are returned in `AgentResult.readRanges` and heartbeated (AD-8). A pure `domain.CoveragePolicy` defines coverage: a changed file is *covered* iff an untruncated `getDiff` of it succeeded, or `FILE` ranges cover all of its `changedHeadRanges`. A deleted file is covered by an untruncated `getDiff`. The report's `coverage` is exactly `{filesChanged, filesCovered, changedLinesTotal, changedLinesCovered, completedRoles, incompleteRoles}`. A role is *completed* iff at least one invocation of it returned `stopReason = MODEL_DONE`. `evaluation` reads these fields and never recomputes them.

---

## H6 — Topic/delegation budget: rejection or run end? (Blocking)

**Pair:** FEATURE-007 (`PlanDecisionPolicy`) vs FEATURE-010 (Workflow loop).

AD-5 says the policy checks per-topic and delegation budgets and "rejected decisions are stored ... After 3 consecutive rejections the run ends `PARTIAL` with `PLANNER_INVALID`." AD-8 says exhausting any limit ends the run `PARTIAL` with `BUDGET_*`. So when the Planner re-proposes an exhausted topic:
- F007's policy returns `Rejected(TOPIC_BUDGET)`, the loop continues, and after 3 such proposals the run ends `PLANNER_INVALID`.
- F010's loop ends the run at once with `BUDGET_TOPIC`.

Both obey an AD, but they give different terminal reasons, a different number of paid Planner calls, and a different decision-trail shape. Related gaps:
- the set of `reasonCode` values is undefined;
- whether `PlanOutcome.Invalid` (malformed after repair) counts as a "consecutive rejection" is undefined;
- whether the Planner is called at all once delegations are exhausted is undefined. Calling it wastes paid calls.
- Topic normalization is a `domain` policy with no algorithm, so F007 might lowercase while F010's tests expect slugs.

**Proposed tightened AD-5**

> **Rule add:** `RejectedDecision.reasonCode ∈ {ROLE_NOT_ALLOWED, ROLE_SINGLE, TOPIC_MISSING, TOPIC_BUDGET, MALFORMED_OUTPUT}`. A `PlanOutcome.Invalid` is recorded as `MALFORMED_OUTPUT` and counts toward the consecutive-rejection limit. `TOPIC_BUDGET` is a rejection, not a run end, and it counts too. Before calling `planNext`, the Workflow checks the run-level limits. If delegations, cost, tokens or elapsed time are exhausted, it ends `PARTIAL` with that `BUDGET_*` code and does **not** call the Planner. `BUDGET_TOPIC` is emitted only when the consecutive-rejection limit is reached and every rejection in that streak was `TOPIC_BUDGET`. Otherwise the limit yields `PLANNER_INVALID`. Topic normalization is exactly `domain.TopicKey.normalize`: NFKC, lowercase, every run of characters outside `[a-z0-9]` becomes `-`, trim `-`, truncate to 64. An empty result is `TOPIC_MISSING`. Neither the Planner adapter nor the dedupe step may normalize topics another way.

---

## H7 — Serialization breaks across the Temporal boundary and the report files (Blocking)

**Pairs:**
- FEATURE-003 (Temporal foundation) / FEATURE-007 (Planner) vs FEATURE-010
- FEATURE-011 report writer vs FEATURE-014 reader

1. **`PlanOutcome` is polymorphic.** AD-6 names `PlanOutcome.Invalid`, which implies a sealed interface. AD-1 forbids Jackson annotations in `domain`, and AD-12 forbids framework objects in payloads. A sealed interface without `@JsonTypeInfo` fails to deserialize in Temporal's Jackson payload converter. F007 can write a sealed interface whose unit tests pass. F010's `TestWorkflowEnvironment` test then fails at the boundary, or F003 adds a custom `DataConverter` with mixins that F007 didn't know about. The same risk applies to any `sealed` `AgentResult` variant.
2. **Two Jackson majors.** Spring Boot 4.x defaults to **Jackson 3** (`tools.jackson`). The Temporal Java SDK's default converter uses **Jackson 2** (`com.fasterxml.jackson`). F011 could write reports with Spring's injected Jackson 3 `JsonMapper`, where dates are ISO strings by default. F014 or the Temporal payloads could use Jackson 2, where dates are timestamps by default. The `Instant` format, map key ordering and null handling then differ between writer and reader, and between a Workflow-time `Instant` in `ReportInput` and how it gets rendered. Both choices obey "Serialization: Jackson".
3. **Byte determinism.** AD-13 needs a fixed property order, sorted map keys and a fixed number format. None is specified.

**Proposed new AD-22 — Payload and report serialization**

> **Rule:** Activity payload records are non-polymorphic. A variant outcome is one concrete record with a `kind` enum and nullable fields. For example, `PlanOutcome{kind: DECISION|INVALID, decision, invalidReason, usage}`. No `sealed` or interface types may cross an Activity boundary. Temporal uses its default Jackson 2 converter, configured once in `orchestration` with the parameter-names and JavaTime modules. Report and manifest JSON are written and read only through one `ReportJson` codec in `adapter.artifacts`. The `evaluation` package reuses that codec or an identical copy guarded by a round-trip test. Its settings are: properties in record-component order, `ORDER_MAP_ENTRIES_BY_KEYS`, `Instant` as ISO-8601 UTC with millisecond precision, money as a decimal string (AD-20), nulls included, UTF-8, `\n` line endings, trailing newline. The Spring-managed Jackson 3 mapper is used only for REST DTOs. The build compiles with `-parameters`.

---

## H8 — `ReportInput` has no owner, contents or final-status rule (Significant)

**Pair:** FEATURE-010 (Workflow) vs FEATURE-011 (reports).

AD-13 says report content "is a pure function of `ReportInput`". Neither the spine nor contracts define `ReportInput`. Open questions:
- Does F010 put raw `AgentResult`s in it, or pre-flattened candidates?
- Does it include the decision trail, rejected decisions, usage ledger, prompt versions (NFR-3), price table (FR-19), reasons and timestamps?
- Who decides the terminal status? F011 might downgrade `COMPLETED` to `PARTIAL` when validation rejects everything. F010 assumes the status it passed in is final.
- What does "no issues found" mean with 0 findings but 3 `NEEDS_REVIEW` candidates?
- What happens if `validateAndReport` itself fails non-retryably? There is no status, reason or `ReportReference` for that case.
- The size of `ReportInput` in history is unbounded by AD-12. It holds 6 invocations × 20 candidates × 15 lines, plus the trail.

**Proposed new AD-23 — `ReportInput` contract**

> **Rule:** `ReportInput` is a `domain` record built only by the Workflow through a pure `domain.ReportInputFactory`. Its fields: `reviewId`, `snapshot`, `mode`, `status`, `reasons`, `startedAt`/`endedAt` (Workflow time), `budget`, `changedFiles`, `invocations: List<InvocationRecord{invocationId, role, topic, task, stopReason, usage, readRanges, candidates, unresolvedQuestions, summarizedWork}>` in invocation order, `acceptedDecisions`, `rejectedDecisions`, `usageLedger`, `promptVersions`, `priceTableId`, `limitations`. `status` and `reasons` are final, and `validateAndReport` never changes them. The report says "no issues found" iff `status = COMPLETED` ∧ `filesCovered = filesChanged` ∧ `findings` is empty ∧ `unverifiedCandidates` is empty. If `validateAndReport` fails after retries, the Workflow ends `FAILED` with `INTERNAL_ERROR`, `report = null`. The serialized size of `ReportInput` must stay ≤ 512 KiB, enforced by a test on the AD-12 maxima.

---

## H9 — Finding provenance shape and dedupe key clash (Significant)

**Pair:** FEATURE-005 (validator/deduper) vs FEATURE-011 (report) / FEATURE-014 (precision counting).

AD-11 says merged findings "keep every provenance". `ReviewFinding` in `contracts.md` has a single `ReviewerRole reviewer`. F005 might add `List<Provenance>`, while F011 renders `reviewer` from the contract record. The dedupe key includes category, so the same off-by-one flagged as `CORRECTNESS` by one role and `DATA_INTEGRITY` by another becomes **two** findings. F014 then counts one of them as a false positive or a duplicate, depending on the author. That inflates the false-positive rate of multi-agent mode specifically. Ordering for `F-001…` ("deterministic order") has no sort key, and "normalized title" has no algorithm.

**Proposed tightened AD-11 (dedupe clause)**

> **Rule replace:** The dedupe key is (`filePath`, overlapping HEAD range of the first verified reference). Category and title are **not** part of the key. A merged finding has `reviewers: List<ReviewerRole>` (sorted, distinct), `provenance: List<{invocationId, role, candidateIndex}>`, the maximum severity, and the category and title of the earliest candidate in invocation order. Final finding order is severity descending, then `filePath`, `startLine`, `title`. Ids `F-001…` are assigned after sorting. The singular `ReviewFinding.reviewer` field is removed.

---

## H10 — Validator vs `ContentPolicy`: which view of the source? (Significant)

**Pair:** FEATURE-004 (git port + `ContentPolicy`) vs FEATURE-005 (validator).

The model saw redacted text (AD-9), so its quote contains `[REDACTED]`. F005 might compare against raw blobs through `RepositoryReadPort`, which F004 might implement without policy because policy "applies to tool results". Every candidate near a secret then drops to `NEEDS_REVIEW` or `INVALID_EVIDENCE`. Other readings are possible. F004 could put the policy inside the port, and then the validator's `readFile` is capped at 200 lines, so a citation of lines 180–230 is unverifiable. A citation of a deny-globbed path has no defined status. The "whitespace-normalized" rule (AD-11) is not defined: collapse runs? strip all whitespace? CRLF?

**Proposed tightened AD-9 / AD-11**

> **Rule add:** `ContentPolicy` applies inside the `adapter.git` implementation of `RepositoryReadPort`, so every caller, the validator included, sees the same redacted and denied view. Tool-size caps (200 lines, 16 000 chars) live in the **tool wrapper**, not the port. The validator reads through the port uncapped, up to the cited range plus ±5 lines. A citation of a refused or denied path → `INVALID_EVIDENCE`, reason `POLICY_DENIED`. Whitespace normalization: CRLF→LF, strip each line's leading and trailing whitespace, collapse internal runs of whitespace to one space, drop empty lines. It is implemented once in `domain.SnippetNormalizer`.

---

## H11 — Candidate and quote caps: who enforces them, and how? (Significant)

**Pair:** FEATURE-006 (adapter parses candidates) vs FEATURE-005 (validator).

AD-12 sets ≤ 20 candidates per invocation and ≤ 15 lines per quote, but no owner and no effect. F006 could truncate a 25-line quote to 15 lines, which F005 then marks partial → `NEEDS_REVIEW`. F005 could reject > 15 lines → `INVALID_EVIDENCE`. Either way the same model output gets a different evidence status, which skews precision. The 21st candidate could be silently dropped, violating FR-15's audit trail, or kept, violating AD-12.

**Proposed tightened AD-12**

> **Rule add:** The `adapter.llm` parser enforces the caps before returning. It keeps the first 20 candidates in model order and records the excess count in `AgentResult.droppedCandidates` (shown in the report). A quote over 15 lines is cut to its first 15 lines and the candidate is flagged `quoteTruncated = true`. The validator treats a truncated quote as a prefix match against the cited range, so truncation alone never lowers the status.

---

## H12 — Idempotency hash and budget snapshot (Significant)

**Pair:** FEATURE-011 (API) vs FEATURE-012 (recovery and duplicate tests).

AD-14 says "SHA-256 of the canonical request payload", but "canonical" is undefined:
- F011 could hash the raw body, while F012 resubmits with reordered keys or an uppercase SHA and expects `200`.
- A server-generated `reviewId` makes every resubmission new. That's fine, but it should be stated.
- AD-8 says `ReviewBudget` comes from config. If F010 reads config in the Workflow (a Spring bean injected into the Workflow impl), replay after a config change is non-deterministic.

**Proposed tightened AD-14 / AD-8**

> **Rule add:** canonical hash = SHA-256(UTF-8 of `repositoryId + "\n" + lowercase(baseSha) + "\n" + lowercase(headSha) + "\n" + mode`), computed after full-SHA resolution in preflight. The API resolves `ReviewBudget` from config **once** at submission and puts it in `ReviewCommand`. The Workflow never reads configuration. Without a caller `reviewId`, there is no idempotency. The progress query returns `ReviewProgress{status, delegationsUsed, delegationsMax, reportedCostMicroUsd, costUpperBoundMicroUsd, maxCostMicroUsd, tokensUsed, elapsedMs, currentStep, reasons}`.

---

## H13 — The Planner has no slice, and the comparable budget is unfair (Significant)

**Pair:** FEATURE-007 (Planner Activity) vs FEATURE-010 (budget check).

AD-8's slice is defined for `AgentTask` only. `PlannerInput` has no tool-call, token, cost or deadline cap, and AD-8's defaults table has no Planner row. A Planner call can grow with the ledger and the model's verbosity. A repair request (AD-6) doubles it. Also, does Planner cost count toward the run's USD 0.50? The PRD (Comparable budget) says the same cost ceiling applies in both modes. If F010 excludes Planner cost, multi-agent gets free orchestration spend.

**Proposed tightened AD-8**

> **Rule add:** `PlannerInput` carries a slice `{maxOutputTokens (default 800), costCap (default USD 0.02), deadline}`. The Planner has no tools. Planner usage counts toward every run-level limit exactly like specialist usage. Before `planNext`, the Workflow requires `remainingCost ≥ plannerCostCap + minSpecialistCostCap`. Otherwise it ends `PARTIAL / BUDGET_COST` without calling the Planner. Run-level token limits get defaults: `maxInputTokens` 400 000 and `maxOutputTokens` 40 000 `[ASSUMPTION]`.

---

## H14 — Units: "lines" and "chars" are undefined (Significant)

**Pair:** FEATURE-001/011 preflight (≤ 1 500 changed lines) vs FEATURE-004 `getDiff` (≤ 400 lines per file) vs FEATURE-014 (coverage %).

Undefined units:
- whether "changed lines" means added+deleted or only added;
- which diff algorithm, and whether whitespace is ignored;
- whether "diff lines" includes context lines and hunk headers;
- whether "16 000 chars" counts UTF-16 units, code points or bytes.

A preflight that counts added lines accepts a submission that a `getDiff` counting all diff lines then truncates. Every file in it is reported uncovered (H5), and the run can't reach `COMPLETED` with full coverage. Each unit obeys its AD.

**Proposed tightened AD-9 (limits clause)**

> **Rule add:** "Changed lines" = added + deleted lines from JGit `HistogramDiff`, no whitespace ignoring, rename detection on, computed by one `adapter.git` function used by both preflight and `getDiff`. "Diff lines per file" counts only `+`/`-` lines, not context lines or headers. "Chars" = Java `String.length()` after redaction. A capped result ends with a fixed marker `…[truncated: N more lines]` and is flagged truncated to the tool wrapper. A truncated result does not count toward coverage (AD-21).

---

## H15 — Fixture, goldset and evaluation ownership (Significant)

**Pair:** FEATURE-013 (fixture + goldset) vs FEATURE-014 (evaluation).

- The goldset schema is deferred to F013, but F014 consumes it.
- FR-23's matching rule ("location agree") has no tolerance. F014 could require an exact range, while F013's goldset records a single line.
- AD-19 says `evaluation` "consumes report files only", yet the structural seed shows `evaluation runner → REST → api`. F014 can't run 3–5 trials without starting reviews.
- The generated fixture repository's location is undefined. If it is generated inside the project's working tree, it becomes a nested `.git` that might be committed or allowlisted inconsistently with `review.allowlist[].path`.
- AD-19's reproducible SHAs depend on the git tool. If the script uses `git` CLI while JGit computes the same SHAs, that works only when the trees and messages are byte-identical, including line endings (`core.autocrlf`).

**Proposed tightened AD-10 / AD-19**

> **Rule add:** F013 owns `evaluation/goldset/{fixture}.json` with schema `{schemaVersion, fixtureId, baseSha, headSha, defects: [{id, isControl, category, filePath, startLine, endLine, rootCause, failureScenario}]}`. F014 consumes it read-only. A finding *location-matches* a defect iff the `filePath` is equal and some evidence range overlaps `[startLine−3, endLine+3]`. Semantic agreement is judged blind, by a human or a recorded rubric. The evaluation runner may call only the public REST API (submit, poll, fetch report) and read report files. It never imports `orchestration`, `application` or `adapter.*`. The fixture script writes repositories to the gitignored `build/fixtures/{name}.git` with `core.autocrlf=false`, and the allowlist points there. A test asserts the expected base and head SHAs.

---

## H16 — Ledger summary: owner, format and trust (Significant)

**Pair:** FEATURE-007 (Planner prompt consumes the ledger) vs FEATURE-010 (Workflow builds it).

AD-12 says the ledger is "built deterministically in the Workflow" and is ≤ 4 000 chars. It doesn't say:
- what format it uses or which fields it includes;
- what gets truncated first;
- whether it is versioned with `getVersion` (a change to the formatter is a Workflow logic change under AD-17, since it alters `PlannerInput` in history only for new runs, but tests need to know);
- that it is untrusted. It contains `summarizedWork` and candidate titles written by the model, which then go into the Planner's prompt. That is a prompt-injection relay between agents (NFR-1).

**Proposed tightened AD-12**

> **Rule add:** The ledger is produced only by pure `domain.LedgerSummarizer.v{n}`. It has one line per invocation, `{n}. {role} topic={topicKey} stop={stopReason} candidates={k} files=[…]`, followed by `summarizedWork` cut to 300 chars per invocation. The oldest invocations are dropped first to stay ≤ 4 000 chars. The adapter wraps it in the Planner and specialist prompts as delimited untrusted data. A change to the summarizer version goes behind `Workflow.getVersion` and comes with a replay history.

---

## Lower-priority observations (no new AD required)

- **Cancellation vs partial `AgentResult`:** a cancelled `reviewSpecialist` returns nothing, so its candidates are lost. That is acceptable, but AD-16 should state that the CANCELLED report contains only results from invocations that completed.
- **`incompleteRoles` in SINGLE mode:** define it as `[SINGLE]` or `[]`. Don't list the three specialist roles there.
- **Prompt version per invocation (NFR-3):** add it to `AgentUsage` (done in H2's proposal) so it reaches the report without a second path.
- **AD-7 vs AD-16:** a 60 s LLM timeout plus a 30 s heartbeat timeout means a heartbeat is due during a single blocking LLM call. Heartbeats must come from a side thread or the call timeout must be < 30 s. Otherwise healthy calls time out and get retried, and billed. Recommend heartbeat timeout ≥ LLM call timeout + 10 s.
- **Status mapping after close:** AD-14 relies on queries against closed Workflows. That works while the namespace retains history. Note the dev-server retention period, and fall back to `report.json` for closed runs that have aged out.

## Method note

Every hole above is a case where two independently built features each obey the spine word for word and still disagree on a shape, an owner, a unit or a state transition. The proposed Rule texts are written to be pasted into the spine. They add or rename fields; for example, `AgentResult` gains `stopReason`, `readRanges`, `droppedCandidates` and `usage.attempt`. So AD-0 (H1) should land first, so `docs/contracts.md` is updated in the same change.
