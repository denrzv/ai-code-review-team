---
title: Rubric review — Architecture Spine, AI Code Review Team
reviewed: ARCHITECTURE-SPINE.md (status draft, 2026-11-21)
against: prd.md (final), addendum.md, docs/ baseline 55169b9
date: 2026-11-21
verdict: 'Strong, mostly well-scoped spine; not ready to bind epics until one timing contradiction and four unset Temporal operating parameters are fixed.'
---

# Rubric review — Architecture Spine

## Verdict

The spine is tight, decision-oriented and resolves almost every inconsistency the PRD addendum raised (cost budget, elapsed-time enforcement, tool names, SINGLE mode, reasons vocabulary, rejected candidates, ReportReference, goldset isolation, idempotency conflicts). It is not yet a safe build substrate: one internal numeric contradiction (heartbeat vs LLM timeout) would cause spurious Activity timeouts and re-billing, the budget defaults make multi-agent runs end `PARTIAL` almost by construction, and several Temporal operating parameters that every orchestration story touches (Activity timeouts and per-kind retry policy, Workflow-id reuse policy, run timeout vs reporting, namespace retention) are neither decided nor deferred.

## Checklist summary

| Criterion | Result |
| --- | --- |
| Fixes the real divergence points | Mostly. Missing: Activity timeouts/per-kind retry, id-reuse policy, Planner budget slice, coverage semantics, validator read view, Jackson generation, evaluation entry point. |
| Rules enforceable and actually prevent the divergence | Mostly. AD-16 contradicts itself; AD-8 defaults defeat their own purpose; AD-14 does not prevent duplicates after close. |
| Nothing deferred lets units diverge | One soft spot: `AgentResult` minimum fields (needed by coverage/usage) are left to "the code". |
| Tech plausibly current | Maven `3.10.1` is odd; Temporal CLI "latest" is unpinned; LangChain4j `1.24.0` / Temporal `1.41.1` need verification; `deepseek-flash` name unverified (already spike-owned). |
| Covers PRD capabilities | Yes, all FR/NFR bound. Map omits explicit NFR-2/3/5 rows; "pinned model parameters" (FR-23) has no home. |
| Operational envelope | Deployment (local), secrets (env), CI (`mvn verify`, fake model) decided. Undecided and not deferred: Temporal namespace retention, task queue/namespace naming, dev-server version, CI platform. |
| Decisions not rationale; no bloat; mermaid; leftovers | Good discipline, no template leftovers. Seed flowchart labels with `{reviewId}` and `\n` likely fail to parse. |

## Findings

### Critical

**C-1 — AD-16 heartbeat timeout (30 s) is shorter than the LLM call timeout (60 s).**
AD-16 heartbeats "on every LLM turn and tool call", so a single LLM call lasting 31–60 s produces no heartbeat and Temporal fails the attempt with a heartbeat timeout. That attempt is retried (re-billing, per the CLAUDE.md caveat) and after 3 attempts (AD-7) the run ends `PROVIDER_ERROR` though the provider was healthy. It also bounds cancellation latency (FR-4) by the longest LLM call, not by the heartbeat timeout.
*Fix:* In AD-16, require heartbeating from a background ticker (e.g. every 10 s) for the duration of any blocking LLM call, in addition to per-turn heartbeats; or set heartbeat timeout > LLM call timeout (e.g. 90 s vs 60 s). State the resulting cancellation bound explicitly ("≤ heartbeat interval + one tool call").

### High

**H-1 — Activity timeouts and per-kind retry policies are not fixed.**
AD-7 gives one RetryPolicy (3 attempts) and AD-16 gives a heartbeat timeout, but no `StartToCloseTimeout` / `ScheduleToCloseTimeout` exists for any of the three Activity kinds, and `validateAndReport` (no LLM, local FS only) silently inherits the 3-attempt LLM policy — a transient FS error then loses the report, contradicting FR-18 "FAILED/CANCELLED still produce a report". FEATURE-007, 010, 011 and 012 will each pick values.
*Fix:* Add to AD-3 (or a new AD) a table per Activity kind: `planNext` and `reviewSpecialist` StartToClose = slice deadline + margin, heartbeat per C-1, AD-7 retry policy; `validateAndReport` StartToClose (e.g. 2 min), unlimited or high-attempt retry with backoff, non-retryable only for a named `ReportSchemaViolation`. All values from `review.*` config.

**H-2 — AD-14 does not prevent a duplicate run after the first run closes.**
Temporal's default `WorkflowIdReusePolicy` is ALLOW_DUPLICATE: re-submitting the same `reviewId` after the Workflow completes starts a *new* run, violating FR-2. In addition, once the namespace retention period expires, describe/query fail and the same `reviewId` is accepted again, overwriting `output/reviews/{reviewId}/` (AD-13).
*Fix:* In AD-14 state `WorkflowIdReusePolicy.REJECT_DUPLICATE` (and the conflict policy for running workflows = fail), handle `WorkflowExecutionAlreadyStarted` by describe + memo-hash compare for both open and closed runs; decide the dev-server namespace retention (e.g. 30 days via `--namespace`/retention config) and state that idempotency and status are guaranteed only within it, falling back to "report exists on disk → 409/200" by comparing the manifest's request hash.

**H-3 — The Workflow run timeout backstop (AD-8) bypasses reporting and the state machine.**
A Workflow run timeout terminates the run server-side: no `validateAndReport`, no `PARTIAL`, no reasons — and Temporal status `TIMED_OUT` (likewise `TERMINATED`, `FAILED`) has no mapping to the API statuses in AD-14/AD-15 or the state diagram. This breaks FR-5 and FR-18 exactly when the elapsed budget misbehaves.
*Fix:* In AD-8, enforce `BUDGET_ELAPSED` with a Workflow timer that cancels the in-flight Activity and proceeds to `validateAndReport`; set the run timeout to elapsed budget + report margin (e.g. 15 + 5 min) as a last resort only. In AD-14, add the mapping: Temporal `TIMED_OUT`/`TERMINATED`/`FAILED` without a report → API `FAILED` with `INTERNAL_ERROR`, no report links.

**H-4 — AD-8 cost defaults make multi-agent runs end `PARTIAL` by construction and leave the Planner unbudgeted.**
Scheduling requires remaining cost ≥ per-invocation cap (USD 0.10). With a USD 0.50 run ceiling, at most 4–5 specialist invocations can ever be scheduled even at zero actual spend once Planner calls are counted, so the "6 delegations" default is unreachable and a Planner wanting to continue ends `BUDGET_COST` rather than `COMPLETED`. This inflates the partial-run count SM-1/FR-23 measures and biases the comparison against multi-agent. Separately, `planNext` has no slice (tool calls, output tokens, cost cap), so the "remaining ≥ cap" check is undefined for Planner calls. No token default is given although `BUDGET_TOKENS` exists and FR-11 requires input/output token limits.
*Fix:* In AD-8 add a Planner slice (e.g. 0 tool calls, max output tokens N, cost cap USD 0.02) and token defaults (per run and per invocation); make the defaults arithmetically consistent (e.g. specialist cap USD 0.06 × 6 + Planner 0.02 × 7 ≤ 0.50), or schedule against an estimated per-invocation cost while the cap stays a hard Activity-side stop. Add a one-line invariant: "defaults must allow `maxDelegations` to be reached under the cost ceiling".

### Medium

**M-1 — Privacy gate runs too late and has no reason code.** AD-18 refuses non-synthetic snapshots inside the LLM adapter, i.e. after the Workflow started; the run then fails with no matching `TerminalReason` and AD-3's preflight list omits it. *Fix:* Add the `synthetic: true` check to AD-3 preflight with a REST problem type `non-synthetic-repository`; keep the adapter check as defense in depth mapping to `INTERNAL_ERROR` (or add `PRIVACY_REFUSED` to AD-15).

**M-2 — `PLANNER_INVALID` with zero coverage ends `PARTIAL`, contradicting the PRD glossary and the state diagram (`PARTIAL` requires coverage > 0).** AD-5 also does not say whether `PlanOutcome.Invalid` (AD-6) counts toward the 3 consecutive rejections. *Fix:* AD-5: "ends `PARTIAL` if coverage > 0, else `FAILED`, reason `PLANNER_INVALID`; `PlanOutcome.Invalid` counts as a rejection".

**M-3 — Coverage semantics are undefined across units.** AD-15 says coverage comes from "read ranges that Activities report", but not whether `getDiff` hunks, `searchCode` hits or `listChangedFiles` count as reading, nor that `AgentResult` must carry them. adapter.git, adapter.llm and domain will disagree. *Fix:* In AD-15 define: a changed line is covered when returned by `readFile` or `getDiff` in a successful invocation; `AgentResult` carries `readRanges: List<FileRange>` produced by the tool layer (not the model).

**M-4 — The validator's read view is unspecified.** `FindingValidator` uses `RepositoryReadPort`, whose results pass through `ContentPolicy` (redaction, 200-line cap). A quote copied from redacted output will not match raw HEAD, or vice versa; a citation spanning >200 lines cannot be checked. *Fix:* AD-11: validator compares against the same redacted view the model saw, via an internal unbounded-range port method not exposed as a tool; state the choice.

**M-5 — Jackson generation is ambiguous.** Spring Boot 4 defaults to Jackson 3 (`tools.jackson`), while the Temporal SDK's default payload converter uses Jackson 2 (`com.fasterxml`). AD-12/Conventions say "Jackson" without naming which, and report writers may pick the other, giving different record/`Instant`/enum handling. *Fix:* Conventions → Serialization: name the Jackson major for Activity payloads (Temporal default converter, or a custom converter) and for reports, and the shared modules/settings (records, JavaTime, ISO instants, enums by name).

**M-6 — Activity cancellation type not fixed.** With the Java SDK default `TRY_CANCEL`, the Workflow proceeds to `validateAndReport` while the cancelled specialist may still be spending until its next heartbeat; its usage is then missing from the report. *Fix:* AD-16: use `ActivityCancellationType.WAIT_CANCELLATION_COMPLETED` for LLM Activities (or state the TRY_CANCEL trade-off and disclose the usage as unknown).

**M-7 — Evaluation runner placement and entry point undecided.** `evaluation` sits in `src/main` of the review app, which therefore ships the goldset reader inside the runtime JVM; nothing forbids other packages importing `evaluation`, and how it is launched (separate main, profile, Maven exec, test source set) is not stated. *Fix:* AD-10/AD-19: ArchUnit rule "no package depends on `evaluation`"; evaluation runs as a separate entry point (e.g. its own `main` or Maven profile) that talks only REST + files.

**M-8 — Temporal dev server unpinned; retention and naming not decided.** "latest CLI" breaks reproducibility (NFR-3) and replay fixtures; namespace, task queue name and retention are cross-unit (client in API, worker in orchestration). *Fix:* Stack: pin the Temporal CLI version; Conventions → Config: `review.temporal.{target, namespace, taskQueue}` with fixed defaults; retention per H-2.

**M-9 — Model sampling parameters are not recorded.** FR-23 requires "pinned model parameters" and NFR-3 reproducibility, but AD-18/manifest record only model id and prompt versions. *Fix:* AD-18: manifest records model id, temperature, top-p, max tokens, thinking flag and endpoint base URL; values come from config, never per call.

**M-10 — Seed flowchart likely fails to render.** Unquoted labels containing `{reviewId}` (braces are shape syntax) and literal `\n` inside `[( … )]` nodes. *Fix:* Quote labels (`out[("output/reviews/{reviewId}/")]`) and use `<br/>` for line breaks.

### Low

**L-1 — Maven `3.10.1` looks wrong** (the 3.x line is 3.9.x; 4.0 is the next line). Verify against Maven Central; pin a real version and the Maven Wrapper.
**L-2 — Verify LangChain4j `1.24.0` and Temporal SDK `1.41.1`** against Maven Central; both are on the high side of expected release cadence. `deepseek-flash` is already spike-owned (FEATURE-002) — keep that note.
**L-3 — Prompt version selection rule missing.** With several `prompts/{role}/v{n}.md`, state that the active version per role comes from config (`review.prompts.{role}`), not "highest n".
**L-4 — Candidate cap overflow behavior** (AD-12: ≤ 20 candidates, ≤ 15 lines) unspecified: state "excess truncated deterministically and recorded in `limitations`".
**L-5 — Dedupe order vs validation** (AD-11) unspecified: state whether dedupe runs on verified findings only or across all buckets.
**L-6 — Capability map omits explicit NFR-2, NFR-3, NFR-5 rows** (they are bound in front matter and ADs). Add rows → AD-2/AD-17, AD-13/AD-18/AD-19, AD-8.
**L-7 — State diagram omits `SUBMITTED → CANCELLED` and `SUBMITTED → FAILED`** (cancel or failure before the first Activity). Add both.
**L-8 — AD-1 still tags ArchUnit `[ASSUMPTION]`** while Stack pins it. Drop the tag or mark the Stack row as assumed.
**L-9 — CI platform and JDK distribution not named** (Conventions/Environment say only "CI runs `mvn verify`"). Name it (e.g. GitHub Actions, Temurin 25) or move to Deferred with owner FEATURE-001.
**L-10 — `AgentResult` minimum fields** are left to "the code" in Deferred, but coverage (M-3), usage (AD-18) and retry visibility (FR-20) depend on them. List the required minimum (`candidates`, `readRanges`, `usage`, `attempt`, `promptVersion`, `rejections`) and defer the rest.
**L-11 — Permanent-error type names** (Conventions: `ApplicationFailure` type = exception simple name) must match `doNotRetry`; list the fixed names in AD-7 so adapter and RetryPolicy cannot drift.

## Counts

Critical 1 · High 4 · Medium 10 · Low 11
