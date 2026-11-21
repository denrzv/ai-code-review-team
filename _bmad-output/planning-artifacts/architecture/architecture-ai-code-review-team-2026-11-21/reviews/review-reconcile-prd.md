---
title: Reconcile review — ARCHITECTURE-SPINE vs PRD + addendum
reviewed: ARCHITECTURE-SPINE.md (status draft, 2026-11-21)
against: prd.md (final), addendum.md (section B, 17 items)
date: 2026-11-21
verdict: NEEDS AMENDMENT (no structural rework; 5 contradictions, ~20 gaps, all fixable with AD amendments, conventions and Deferred lines)
---

# Reconcile review: spine vs PRD

## Verdict

The spine is strong on its main path. Every FR and NFR is listed in `binds`, and 13 of the 17 addendum-B items are resolved outright. But **five statements contradict the PRD**, and several "quiet" requirements (model-assigned severity, exactly-once disclaimer, line coverage, manifest contents, Planner decision-quality data, blind judging, retention of trial artifacts) are either missing or only implied. None of these needs a paradigm change. They need AD amendments, a few new conventions and some explicit Deferred lines.

Severity legend: **C** = contradicts PRD, **H** = PRD requirement with no architectural home (an implementer would guess), **M** = partly covered or ambiguous, **L** = cosmetic or traceability only.

---

## 1. Contradictions with the PRD

### C1 — Shifted line references become `NEEDS_REVIEW`, but the PRD says `INVALID_EVIDENCE`
- **Spine:** AD-11: "the quote sits within ±5 lines → `NEEDS_REVIEW`".
- **PRD:** FR-14: "A candidate with a fabricated **or shifted** line reference is marked `INVALID_EVIDENCE`". §3 glossary: `NEEDS_REVIEW` = "location exists, but the snippet only partly matches or the claim spans beyond the cited range". SM-2 "fake line" scenario and SM-4 depend on this.
- **Fix (AD-11 amendment):** "A quote found only outside the cited range (shifted citation) → `INVALID_EVIDENCE` with reason `LINE_SHIFTED{actualStart}`. `NEEDS_REVIEW` only when (a) the cited range exists and ≥ 1 normalized quote line matches inside it but not all lines do, or (b) the quote matches inside the range but the candidate's claimed range is wider or narrower than the quote by > N lines `[ASSUMPTION N=3]`." Also define "partial match" numerically, e.g. the share of normalized quote lines found in order within the range is ≥ 0.5 `[ASSUMPTION]`. If the ±5 tolerance is wanted on purpose, it is a PRD change and needs an ADR. The spine cannot override FR-14 silently.

### C2 — "No issues found" is gated on file coverage only; the PRD defines coverage as files **and** lines
- **Spine:** AD-15: "only for `COMPLETED` with 100% changed-file coverage".
- **PRD:** §3 Coverage = "share of changed files, **and** of changed lines, that at least one agent read through the tools"; FR-5 "did not cover the whole diff".
- **Fix (AD-15 amendment):** "`Coverage{changedFiles, filesRead, changedLines, linesRead}` computed in `domain`. A changed (head-side added or modified) line counts as read when a `readFile` range or a `getDiff` hunk returned to the model included it, before truncation. A truncated result (AD-9 caps) counts only the returned lines. 'No issues found' needs `COMPLETED` ∧ filesRead = changedFiles ∧ linesRead = changedLines." Activities report read ranges in `AgentResult.readRanges` (already implied by AD-15, now explicit). Deleted-only lines are excluded (PRD §6) and the report says so.

### C3 — A budget exhausted with zero coverage ends `PARTIAL`; the PRD's `PARTIAL` requires some coverage
- **Spine:** AD-8: "Exhausting any limit ends the run `PARTIAL`". AD-5: 3 rejections → `PARTIAL` `PLANNER_INVALID` regardless of coverage. Only AD-7 checks coverage > 0.
- **PRD:** §3 PARTIAL = "stopped early … **after achieving some coverage**". FR-11 says limit → `PARTIAL` (an internal PRD tension the spine must settle).
- **Fix (new rule in AD-15):** "One terminal-state function in `domain`: `terminalState(cause, coverage)`. Any early stop (budget, provider, planner-invalid) with linesRead > 0 → `PARTIAL`; with linesRead = 0 → `FAILED`. The reason code is kept either way." Then AD-5, AD-7 and AD-8 reference it instead of naming states. Add to the state diagram: `RUNNING → FAILED: budget / planner invalid with coverage = 0`.

### C4 — The per-invocation cost cap plus Activity retries can break the run ceiling (SM-5, NFR-5)
- **Spine:** AD-8: the `SINGLE` cost cap per invocation is USD 0.50, equal to the whole run ceiling. AD-7 allows 3 attempts. The Activity enforces the slice with in-memory counters, which reset on every retry, and the slice is checked only after a call returns, so the last call can overshoot.
- **PRD:** NFR-5 "hard estimated-cost ceiling"; SM-5 "no run exceeds its cost ceiling".
- **Fix (AD-8 amendment):**
  1. "Slice spend is cumulative across attempts. The Activity heartbeats `SliceUsage{tokensIn, tokensOut, costUsd, toolCalls}` as heartbeat details after every LLM turn. On attempt > 1 it resumes counters from `getHeartbeatDetails`. Spend is lost only for the call in flight at the crash, and the report marks it unknown."
  2. "Before each LLM call the Activity checks a worst-case projection (current prompt tokens × input price + `maxOutputTokens` × output price) against the remaining slice. If the call would not fit, it stops with the slice-exhausted outcome."
  3. "The Workflow charges each completed invocation its reported cost plus, for each attempt whose usage is unknown, a pessimistic charge of one worst-case call."
  4. Change the `SINGLE` cost cap to ≤ run ceiling minus the report/headroom reserve, or keep it but rely on (1)–(3). Write down that SM-5 is measured on *estimated* cost, plus a known-unknown term for lost attempts.

### C5 — Trial artifacts are gitignored, but FR-23 says "every trial's reports and manifest are kept"
- **Spine:** structural seed: `output/reviews/{reviewId}/  # gitignored artifacts`.
- **PRD:** FR-23 "Every trial's reports and manifest are kept"; SM-1 reproducible evidence; FR-24 publish.
- **Fix (AD-19 amendment, or new AD-20 Evaluation):** "The evaluation runner copies each trial's `report.json`, `report.md` and `manifest.json` into `evaluation/results/{experimentId}/{mode}/trial-{n}/` (committed), together with `experiment.json` (model params, budget, fixture SHAs, trial→reviewId map) and the judging sheets. `output/` stays gitignored as scratch."

---

## 2. Quiet requirements that did not land

### H1 — Severity labeled as model-assigned (FR-18)
- **Missing:** nothing in the spine mentions severity.
- **Fix (convention row "Report semantics"):** "Report field is `modelSeverity` (enum `LOW|MEDIUM|HIGH|CRITICAL`). Markdown renders it as 'Severity (model-assigned)'. Every report carries the fixed limitation strings `SEVERITY_IS_MODEL_ASSIGNED` and `SOURCE_VERIFIED_PROVES_LOCATION_NOT_BUG`." No validator touches severity.

### H2 — "Never claim exactly-once" is not a report-level obligation (FR-19, NFR-2, project CLAUDE.md caveat)
- **Spine:** AD-8 discloses lost-attempt usage as unknown. No rule makes the report *state* the disclaimer, and NFR-2's "if Temporal storage is lost, guarantees are void" is not recorded anywhere.
- **Fix (AD-13 amendment):** "Every report and manifest includes fixed limitation codes `AT_LEAST_ONCE_LLM_BILLING` ('a provider request may be billed more than once if an attempt is retried after a lost acknowledgment') and `DURABILITY_REQUIRES_TEMPORAL_STORAGE`. Report text must never use 'exactly once'. A unit test greps the Markdown template for the phrase."

### H3 — Retries counted and disclosed per invocation (FR-19, FR-20 "retry visible in the report")
- **Spine:** AD-18 records the attempt number in `AgentUsage`. That alone does not say the report shows retried attempts, or how a *crashed* attempt (which never returns) becomes visible.
- **Fix (AD-18 amendment):** "`AgentResult`/`PlanOutcome` carry `attempt = ActivityInfo.getAttempt()`, the last heartbeated `SliceUsage` of prior attempts, and `priorAttemptsUsageKnown: boolean`. The report shows per invocation: `attempts`, `retriedAttempts = attempts − 1`, retry causes where known (e.g. heartbeat timeout = worker loss, `PROVIDER_429`), and a run total `retriedAttemptsTotal`. Run-level totals split `costUsdEstimated` and `costUsdUnknownAttempts`." This also covers SM-2 "worker restart" evidence.

### H4 — Actual provider usage vs estimates (FR-19)
- **Spine:** AD-18 records "estimated cost from the configured price table". It does not distinguish provider-reported token counts from local estimates.
- **Fix (AD-18 amendment):** "`AgentUsage.tokenSource ∈ {PROVIDER_REPORTED, ESTIMATED}` per call. Cost is always named `estimatedCostUsd` (DeepSeek returns no cost). Markdown labels estimates." The price basis in the manifest is already covered: AD-18 "price table and its date go into the manifest". Add "with currency and the cache-hit/miss input price distinction when the provider reports cache tokens".

### H5 — Manifest contents are not enumerated (glossary "Manifest", NFR-3, FR-23 "pinned model parameters")
- **Spine:** the manifest is referenced (AD-13, AD-18), but its fields are never listed. Model parameters (temperature, top_p, max tokens, seed if supported), the resolved budget, the fixture ID and the application build SHA are absent.
- **Fix (convention row "Manifest"):** `schemaVersion, reviewId, requestPayloadSha256, repositoryId, fixtureId, baseSha, headSha, mode, model{provider, id, temperature, topP, maxOutputTokens, thinking:false}, promptVersions{role→v}, budget (resolved), toolLimits, priceTable{date, currency, prices}, appGitSha, workflowVersionMarkers, reportFiles{path, sha256}, terminalState, reasons, limitations`. NFR-3 test: rerunning from the manifest reproduces the same inputs.

### H6 — Planner decision-quality metrics have no data contract (FR-23, SM-1, SM-C3)
- **Missing:** the evaluation package "consumes report files only", but nothing guarantees that the report carries enough to compute "Planner decision quality".
- **Fix (AD-15 or new AD-20, plus a Deferred line):** "Report JSON has `decisionTrail: List<DecisionTrailEntry{seq, kind(ACCEPTED|REJECTED|INVALID_OUTPUT|COMPLETE), role, task, topic, normalizedTopic, rationale(≤ 500 chars), reasonCode, invocationId, outcome{candidates, sourceVerified, needsReview, invalid, costUsd}}>`. Decision-quality metrics, defined in `evaluation` at FEATURE-014: rejected-decision rate, repeat-topic rate, share of delegations yielding ≥ 1 goldset-matched finding, cost per matched defect per role, premature COMPLETE (COMPLETE while coverage < 100%)."

### H7 — Rejected/unverified candidate reasons and silently dropped candidates (FR-15, FR-25)
- **Spine:** AD-11 gives `rejectedCandidates` "with a reason". It gives no reason for `unverifiedCandidates`, and no home for candidates dropped *before* validation: over the AD-12 caps (> 20 per invocation, snippet > 15 lines), malformed after repair (AD-6), or base-side.
- **Fix (AD-11 + AD-12 amendments):** "Every candidate an Activity received gets exactly one disposition: `FINDING`, `UNVERIFIED{reasonCode}`, `REJECTED{reasonCode}`, `DROPPED{reasonCode: OVER_CANDIDATE_CAP | SNIPPET_TOO_LONG | MALFORMED | DUPLICATE_MERGED}`. Reason codes are an enum in `domain` (`QUOTE_PARTIAL`, `RANGE_MISMATCH`, `LINE_SHIFTED`, `FILE_NOT_IN_HEAD`, `QUOTE_NOT_FOUND`, `BASE_SIDE_UNSUPPORTED`, …). Dropped candidates appear as counts plus reason in the decision trail. Nothing disappears silently."

### H8 — `FAILED` minimal report and status after the Workflow closes (FR-18, FR-3, NFR-6)
- **Spine:** AD-13 says the same Activity produces the minimal report. AD-16 covers the `CANCELLED` path. Not covered: (a) `FAILED` from an unexpected exception or a non-retryable Activity failure, (b) `validateAndReport` itself failing, (c) mapping Temporal execution status to review status. A Workflow that *returns* `FAILED`/`PARTIAL` shows as `COMPLETED` in describe, so "describe status after close" (AD-14) is wrong for PARTIAL and FAILED. (d) The Temporal dev server's namespace retention is finite, so status and the AD-14 idempotency check disappear after retention.
- **Fix (AD-14 + AD-15 amendments):**
  - "The Workflow catches `ActivityFailure` and other non-cancellation exceptions at the top level, computes `FAILED{INTERNAL_ERROR|PROVIDER_ERROR}`, runs `validateAndReport` (minimal), and **returns** `ReviewOutcome{state, reasons, coverage, reportReference}` normally. Only `CANCELLED` ends via `CanceledFailure`, after the detached report."
  - "If `validateAndReport` exhausts retries, the Workflow returns `FAILED{REPORT_WRITE_FAILED}` with no reportReference." Add the code `REPORT_WRITE_FAILED` to the AD-15 enum.
  - "Status after close = the Workflow result (`ReviewOutcome`), not the execution status."
  - "After retention, `GET /reviews/{id}` falls back to `output/reviews/{id}/manifest.json`, and idempotency (AD-14) also compares against the manifest's `requestPayloadSha256`. Set dev-server namespace retention explicitly `[ASSUMPTION 30d]`."

### H9 — Idempotency after the Workflow closes (FR-2)
- **Spine:** AD-14 compares hashes "if already started". With Temporal's default `WorkflowIdReusePolicy` (ALLOW_DUPLICATE), a resubmission after the run *closes* starts a second run.
- **Fix (AD-14 amendment):** "Start with `WorkflowIdReusePolicy.REJECT_DUPLICATE` (and `WorkflowIdConflictPolicy.FAIL`). On `WorkflowExecutionAlreadyStarted` (open or closed), describe → compare the memo hash → `200` existing / `409`. First successful start → `202 Accepted` + `Location: /reviews/{reviewId}`" (FR-1 "accepted status … location to poll" is currently missing; AD-14 lists only 200/409).

### H10 — Evaluation protocol: distinct trial ids, blind judging, matching rule (FR-23, addendum B-17)
- **Spine:** "report matcher, metrics" implies automated matching. The PRD's matching rule is semantic (root cause + location + plausible failure scenario), applied blind to mode. Repeated trials on the same snapshot would also collide on `reviewId` idempotency unless the ids differ.
- **Fix (new AD-20 "Evaluation is offline over kept artifacts"):** "Trial `reviewId = {experimentId}-{mode}-t{n}`. The runner submits through REST only, with the same `ReviewBudget`, model params and fixture SHAs for both modes. The matcher produces *mode-blinded* judging sheets (findings shuffled, mode and agent provenance stripped, keyed by an opaque id). It pre-fills location agreement automatically. The operator records root-cause and scenario verdicts. Metrics (recall = matched/9, precision = valid/shown, FP per run, coverage, latency, cost, decision quality, partial/failed counts, mean ± sd) are computed from the judged sheets. The non-defect control counts as an FP if flagged."

### H11 — Token budgets have no defaults; "input and output tokens" is only half-enforced (FR-11, B-14)
- **Spine:** AD-8 has `BUDGET_TOKENS` but no run-level token default. The slice carries only "max output tokens", with no input-token limit.
- **Fix (AD-8 defaults table):** add `Input tokens per run` and `Output tokens per run` (e.g. 400 k / 40 k `[ASSUMPTION]`), `Max output tokens per call` (e.g. 2 k), `Repair attempts = 1` (FR-17, a fixed constant and not configurable above 1), `Planner invocations = delegations + 1`. Put input tokens in the slice too.

### H12 — Cancellation bound is not stated, and AD-16's timeouts conflict internally (FR-4)
- **Spine:** heartbeat timeout 30 s but LLM call timeout 60 s, with heartbeats sent "on every LLM turn". A single blocking 45 s call trips the heartbeat timeout, which causes a spurious retry and double billing. FR-4 asks for a *bounded* time, and the bound is never stated.
- **Fix (AD-16 amendment):** "Heartbeat from a scheduled ticker every 10 s while an LLM call blocks (or set heartbeat timeout > LLM call timeout, e.g. 90 s). Cancellation reaches the Activity at the next heartbeat. The Activity aborts the in-flight HTTP call and does not start another. Stated bound: no new LLM request starts more than heartbeat interval + one tool call after cancel. The in-flight request may still complete and bill." Activities must also be started with `ActivityCancellationType.WAIT_CANCELLATION_COMPLETED` (or `TRY_CANCEL` plus a documented trade-off).

### H13 — Durability and safety scenario suite (SM-2, SM-3)
- **Missing:** the Testing convention mentions the fake `ChatModel` and `TestWorkflowEnvironment`, but there is no scenario inventory, and CI has no Temporal server, so the "worker restart" scenario cannot be shown there.
- **Fix (convention row "Acceptance scenarios"):** "One tagged test class per SM-2 scenario (worker restart, Planner replay, duplicate report write, rate limit, bad schema, fake line, budget exceeded, cancel) and per SM-3 fixture (traversal, symlink escape, prompt injection in source/diff, plus an ArchUnit/reflection test that no tool method writes, execs or opens sockets). Worker-restart runs against the CLI dev server as an opt-in `@Tag("durability")` demo test, which kills and restarts the worker. The rest run in-process in CI."

---

## 3. Partly covered or ambiguous (M)

| # | PRD source | What is missing | Proposed spine fix |
| --- | --- | --- | --- |
| M1 | FR-1, FR-6 | REST error types lack `no-committed-changes` (empty diff / base = head, the "uncommitted-only" case), `invalid-request` (bad reviewId or abbreviated SHA), `repository-not-synthetic` | Extend the Errors (REST) convention row. Preflight rejects non-40-hex SHAs rather than resolving refs |
| M2 | FR-9 | "Trying to call tools directly" — the spine does not say the Planner runs with **no tools** | AD-5: "`planNext` sends no tool specifications. A response containing tool calls → `INVALID_OUTPUT{PLANNER_TOOL_CALL}`." Also state whether `PlanOutcome.Invalid` counts toward the 3-consecutive-rejection limit (recommend yes) |
| M3 | FR-10 | Specialist must return open questions + work summary; `AgentResult` minimal fields are deferred to code, and the report has no place for open questions | AD-12: minimum `AgentResult{candidates, openQuestions(≤ 5 × 300 chars), workSummary(≤ 1 000 chars), readRanges, usage, attempt}`. Report section "Open questions" |
| M4 | FR-12 | Ledger content is unspecified (what the Planner sees: coverage gaps? topics done?) | AD-12: ledger = per invocation {role, topic, workSummary, candidate counts by status, files read}, plus current coverage gaps (unread changed files), truncated oldest-first |
| M5 | FR-16 | Dedupe merges *across* agents, but the spine says nothing on how differing `modelSeverity`/category are reconciled | AD-11: merged finding keeps max severity, records all `{agent, invocationId, severity}` provenance, and orders deterministically |
| M6 | NFR-1 | LLM text (titles, rationale, snippets) is rendered into Markdown, so a Markdown/HTML injection channel exists | Convention: Markdown writer escapes HTML and link syntax in model-sourced strings, and fences snippets |
| M7 | NFR-2 | "If storage is lost, guarantees are void" is not recorded | Covered by H2 limitation code. Also add a Structural Seed note: `temporal.db` location is outside `output/` and backup is not provided |
| M8 | FR-26 / OQ-2 | Heartbeat-timeout retries and provider retries share one RetryPolicy, so a worker crash consumes a provider retry attempt | AD-7: state it explicitly, or give `reviewSpecialist` 4 attempts. Whichever is chosen, it is disclosed via H3 |
| M9 | NFR-7 | Traceability per attempt is covered for logs. Is it also in the report? | AD-18: per-attempt `AgentUsage` rows appear in report.json (not only logs) |
| M10 | FR-3 | Progress query fields are unnamed | AD-14: `ReviewProgress{state, delegationsDone, delegationsMax, costUsedUsd, costMaxUsd, tokensUsed, elapsedMs, coverage, reportReference?}` |
| M11 | §2.2, NFR-4 | `synthetic: true` gate exists (AD-18), but the preflight also ought to refuse non-synthetic repos before any LLM call (FR-1 "before any LLM call") | AD-3 preflight list: add the "allowlist entry `synthetic: true`" check. AD-18's gate stays as defense in depth |

---

## 4. Addendum B — 17 inconsistencies

| B# | Item | Spine status | Action |
| --- | --- | --- | --- |
| 1 | No cost field in `ReviewBudget` | Resolved (AD-8 `maxEstimatedCostUsd`) | — |
| 2 | Elapsed time vs no wall clock | Resolved (AD-2, AD-8 `Workflow.currentTimeMillis()` + run timeout) | — |
| 3 | Tool name drift | Names resolved (AD-9). **`searchCode` signature not fixed** | AD-9: `searchCode(String literalOrRegex, Optional<String> pathGlob) → ≤ 20 hits {path, line, text}`, `findRelatedTests(String path)`. Or a Deferred line naming FEATURE-004 as owner |
| 4 | No FEATURE owns `SINGLE_AGENT` | Architecturally resolved (AD-4). FEATURE ownership not mentioned | Deferred: "Epics must add a FEATURE owning `SINGLE_AGENT`/`SINGLE` prompt before the FEATURE-014 evaluation" |
| 5 | Loop has no `SINGLE` case / Planner may pick `SINGLE` | Resolved (AD-4) | — |
| 6 | Reasons named three ways | Resolved (AD-15) | — |
| 7 | No rejected candidates; `NEEDS_REVIEW` undefined | Resolved, but see **C1** and **H7** | Apply C1/H7 |
| 8 | "VERIFIED" vs `SOURCE_VERIFIED` | Spine uses the right name. No instruction to fix `docs/architecture.md` | Deferred/convention: "Enum names in this spine are canonical. `docs/architecture.md` is corrected in FEATURE-001 (ADR note)" |
| 9 | `ReportReference` lacks manifest; ambiguous sha256 | Resolved (AD-13) | — |
| 10 | Dependency table vs diagram for FEATURE-011/012/014 | **Not addressed** | Deferred: "FEATURE dependency graph is re-derived in epics. Spine constraint: FEATURE-014 depends on SINGLE-mode feature + AD-19 fixture + AD-20 evaluation" |
| 11 | No synthetic repo before FEATURE-013 | Resolved (AD-19 tiny fixture). The "who" (OQ-7) is epics | Deferred line: OQ-7 owner = epics |
| 12 | Goldset next to fixture | Resolved (AD-10) | — |
| 13 | Where `SUBMITTED` lives; undefined types | `SUBMITTED` resolved (AD-14). Undefined types only partly resolved (named but fields deferred) | Add a short type inventory (record names + owning package) to the conventions, or link H5/H6/M3/M10 field lists |
| 14 | Missing numeric defaults | Mostly resolved. Tokens, repair attempts and cancellation bound missing | H11, H12 |
| 15 | Idempotency conflict semantics | Resolved (AD-14), but closed-run and retention holes remain | H8, H9 |
| 16 | No approval path | Deferred (non-goal) | — |
| 17 | Precision/recall matching rules differ | **Not addressed** in the spine (evaluation package unspecified) | H10 (AD-20) |

---

## 5. FR / NFR / SM / OQ coverage check

- **FR-1..26:** all bound. Weak spots: FR-1 (202/Location, error types: H9, M1), FR-4 (bound: H12), FR-5 (line coverage: C2), FR-10 (open questions: M3), FR-11 (tokens: H11; zero coverage: C3), FR-14 (C1), FR-15/25 (H7, H6), FR-18 (severity: H1; FAILED: H8), FR-19 (H2–H4), FR-23 (H5, H10, C5), FR-24 (C5: no committed write-up location; add `evaluation/results/{experimentId}/REPORT.md` to AD-20).
- **NFR-1..7:** bound. The Capability Map lacks rows for NFR-2, NFR-3 and NFR-5 (L: add rows → AD-2/AD-7/AD-17, AD-13/H5, AD-8/C4).
- **SM-1..5, SM-C1..3:** not listed in `binds`. SM-1 → H10/C5. SM-2/SM-3 → H13. SM-4 → C1. SM-5 → C4. Counter-metrics: AD-20 should state that finding count, `SOURCE_VERIFIED` rate and delegation count are reported but never used as optimization targets (L).
- **OQ-1** Deferred to FEATURE-002 ✓. **OQ-2** AD-7 ✓ (pending spike). **OQ-3** Deferred ✓. **OQ-4** AD-13 ✓. **OQ-5** Deferred ✓. **OQ-6** AD-8 ✓. **OQ-7** AD-19 architecture ✓, owner → epics (B-11).

## 6. Low-severity / traceability

- L1: add `SM-*` and `OQ-*` to frontmatter `binds` (several ADs already cite OQ-4/OQ-6/OQ-7 and SM-4).
- L2: AD-4 `[ASSUMPTION]` "SINGLE uses same ReviewBudget": with a 30-tool-call and USD 0.50 slice, comparability rests on the *run* ceiling (PRD "comparable budget = same estimated-cost ceiling"). State that explicitly so nobody "equalizes" delegations.
- L3: project rule 10 (CLAUDE.md) requires ADRs for architectural changes. AD-10, AD-11 tolerances, AD-14 and AD-15 diverge from `docs/` at baseline and should each get an ADR stub, or a Deferred line saying ADRs are written at FEATURE-001.
- L4: the AD-12 snippet cap (≤ 15 lines) vs the PRD's "claim spans beyond the cited range" → a candidate citing 30 lines cannot quote them all. Define that the quote may be a ≤ 15-line excerpt *within* the cited range, and that range length > 50 → `NEEDS_REVIEW{RANGE_TOO_WIDE}`.
