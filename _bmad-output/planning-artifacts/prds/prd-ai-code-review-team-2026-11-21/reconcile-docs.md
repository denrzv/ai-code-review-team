# Input Reconciliation: prd.md + addendum.md vs. original inputs

Baseline inputs: `README.md`, `docs/architecture.md`, `docs/adr/README.md`, `docs/contracts.md`, `docs/implementation-plan.md`, `docs/evaluation.md`, `docs/assumptions-and-risks.md`.

Scope: requirement-level content that is **missing** from the PRD + addendum, or that they **contradict**. Pure implementation detail is ignored. The six confirmed assumptions (reviewId conflict, goldset isolation, cost budget, single-agent mode, rejected-candidates section, the USD 0.50 ceiling) are not flagged.

Severity: **high** means a success metric or core intent has no requirement behind it. **medium** means a behaviour the inputs require is unspecified or only implied. **low** means a minor omission or a wording issue.

---

## Top gaps (cross-input)

| # | Gap | Severity | Sources |
|---|---|---|---|
| T1 | No FR covers transient provider failures (429 or 5xx). The inputs require classified, bounded retry with backoff, no stacked HTTP and orchestrator retries, and a FAILED or PARTIAL result when retries run out. SM-2 tests RATE_LIMIT, but no FR backs it. | high | architecture (failure table), evaluation (RATE_LIMIT), risks (double billing), contracts (pricing and retries) |
| T2 | The evaluation is under-specified. FR-23 leaves out the planner-quality, durability and safety metrics. There is no adjudication protocol (blind or consistent, matching by root cause, location and failure scenario), and nothing says all trial outputs must be kept. | medium | evaluation (metrics, protocol 4, 5, 2), ADR-007 |
| T3 | Transparent control is not a requirement. Nothing makes the sequence of Planner decisions (role, task, rationale, rejected decisions) or the candidate rejection reasons inspectable in the report or the run record. | medium | ADR-003 (transparent execution, no hidden delegations), implementation-plan QA checklist, A-01 |
| T4 | "Coverage" is used but never defined. Oversized or large-diff input has no specified outcome: the inputs say it should end PARTIAL with a reason, not fail silently or truncate. | medium | contracts (coverage object), architecture (caps on changed files and duration), risks (large diffs → PARTIAL), A-14 |
| T5 | The durability demo's preconditions and its limits are missing. It needs a persistent orchestrator store, and the PRD should say plainly that losing that store voids the guarantees. The API-crash-after-start scenario is also absent. | medium | README, architecture (failure table), ADR-008, A-05 |

---

## README.md

| Finding | Type | Severity | Suggested PRD fix |
|---|---|---|---|
| Durability tests require orchestrator storage that survives a worker restart. An ephemeral setup would make SM-2 meaningless. | missing | medium | Add to FR-20 or SM-2: "Durability scenarios run against an orchestrator with persistent state. Results from ephemeral state do not count." (T5) |
| The model or provider is configurable (base URL, model, key from the environment) and switchable. | missing | low | Add an NFR, or a line in the addendum: provider and model are external configuration, and the model ID is recorded per run (NFR-3 already records it). |
| "Reproducible" is the headline attribute. | covered | — | NFR-3 covers it. No action. |

## docs/architecture.md

| Finding | Type | Severity | Suggested PRD fix |
|---|---|---|---|
| Failure table: DeepSeek 429 or 5xx needs classified retry with backoff and must not stack unbounded HTTP and orchestrator retries. | missing | high | New FR (e.g. FR-17a "Transient provider failures"): classified, bounded retry with backoff. Permanent errors are not retried. If the retries or the budget run out, the run ends PARTIAL or FAILED with the reason. Every attempt is counted (link to FR-19). Map it in SM-2. (T1) |
| Failure table: the API process dies after start, and the review continues. | missing | medium | Add to FR-20: "A review that has started keeps running if the submitting API process dies. Resubmitting it follows FR-2." (T5) |
| Failure table: if orchestrator data is lost, there is no durability guarantee (honesty about limits). | missing | medium | Add to NFR-2: "If orchestrator persistence is lost, durability is void, and the PRD claims no recovery for that case." (T5) |
| Caps on the number of changed files and on total review duration. Oversized input must give PARTIAL, not a misleading result. | missing | medium | Add to FR-5 or FR-11: if the input is over the limits (changed files, diff size), the run ends PARTIAL with the uncovered files listed, or is rejected at submission. Choose one. (T4) |
| Each run is bound to its own Snapshot, and no review context is shared between runs, so concurrent reviews must not contaminate each other. | missing | low | Add to FR-6: "Concurrent runs are isolated; a tool call never reads another run's Snapshot." |
| Git objects for the pinned SHAs must stay available until the run completes. | missing | low | Add an assumption: the fixture repo keeps its pinned objects reachable, and an unreachable SHA during a run gives FAILED with a reason. |
| The Review API is local-only for the PoC. | missing | low | Add to §2.2 or §6: "The API is reachable only locally; no authentication model in v1." |
| A nested supervisor would hide delegations; the delegation trail must stay observable. | missing | medium | See T3. Add FR-3a / FR-18 content: the report and the run status list each Planner decision (role, task, topic, rationale) and each rejected decision. |
| The architecture says "VERIFIED" where the enum says `SOURCE_VERIFIED`. | doc inconsistency | low | Already in addendum B8. No PRD change. |

## docs/adr/README.md

| Finding | Type | Severity | Suggested PRD fix |
|---|---|---|---|
| ADR-003 trade-off: "transparent execution/recovery at Activity boundaries", with every delegation visible. | missing (qualitative intent) | medium | Add "transparent control" to Vision (§1) as an explicit quality priority, and back it with the delegation-trail requirement (T3). |
| ADR-010: the Planner's *task scope* is validated, not only its role and budget. Per-role checks apply. | partially missing | low | Extend FR-9: decisions whose task falls outside the delegated role's scope, or outside the Snapshot, are rejected and recorded. |
| ADR-005 trade-off: no dynamic verification. Defects that need runtime behaviour may be missed. | missing (honesty) | low | Add to the report limitations (FR-18): "static, read-only analysis; runtime-only defects may be missed." |
| Status legend: "Decisions are revisitable after evidence from evaluation" (learning loop). | missing (qualitative intent) | low | Add to FR-24: the write-up states which architectural decisions the results support or call into question. |
| Open decision: whether reports store source snippets or digests if sensitive code is ever supported. | missing | low | Add to Open Questions, or note in §6 that v1 reports store snippets because inputs are synthetic. |
| Open decision: how to persist billing estimates when a retry makes client-side accounting uncertain. | covered | — | FR-19 discloses retries. Optionally add to OQ-4. |

## docs/contracts.md

| Finding | Type | Severity | Suggested PRD fix |
|---|---|---|---|
| The coverage object (files reviewed vs files changed, completed vs incomplete roles) gives "coverage" its meaning. The PRD uses the word but never defines it. | missing | medium | Add "Coverage" to the Glossary: changed files inspected out of changed files, plus roles or categories completed and incomplete. FR-5's "whole diff" test then refers to it. (T4) |
| "Do not equate model-assigned severity with proven business impact." | missing (honesty) | medium | Add to FR-18 or §6: severity is labelled as model-assigned and is not proven impact. |
| `unverifiedCandidates` must be kept distinct from *rejected* candidates. | partially missing | low | Refine FR-15: the report separates rejected (`INVALID_EVIDENCE`, with reason) from unverified (`NEEDS_REVIEW`). Define what `NEEDS_REVIEW` means (addendum B7). |
| Base and head existence and the ancestry/compare policy are validated. | partially missing | low | Extend FR-1: base and head must exist and satisfy the comparison policy (e.g. base is an ancestor of head), otherwise reject. |
| Report endpoint: the stored report is available for COMPLETED or PARTIAL runs. | covered | — | FR-3 and FR-18. No action. |
| A breaking orchestration change is a replay/versioning concern and needs a replay test. | missing | low | Add to NFR-2 or SM-2: in-flight runs survive a compatible code change, checked by a replay test (or declare this out of scope for v1). |
| The Planner role enum includes SINGLE, so multi-agent mode could pick it. | contradiction risk | low | Add to FR-9: in multi-agent mode only Correctness, Security and Performance are valid targets. (Addendum B5 already notes the doc gap.) |

## docs/implementation-plan.md

| Finding | Type | Severity | Suggested PRD fix |
|---|---|---|---|
| QA checklist: the report shows the *candidate rejection reasons*. | missing | medium | Extend FR-15: each rejected or unverified candidate carries a machine-readable reason. (T3) |
| QA checklist: the read-tool calls the agent actually made are visible. | missing | low | Extend FR-10 or NFR-7: each invocation records its tool-call count and tool names, so evidence can be traced to real reads. |
| FEATURE-006 is Correctness-only, but the PRD wants an all-category single agent. | known divergence | — | Already an assumption (FR-13) and addendum B4. No action. |
| PR bar: "Include before/after usage/time metrics when agent behavior changes." | process | low | Optional. Add to §8 as a guard: changes to prompts or roles are compared on cost and latency. Can stay out of the PRD. |
| M1 spike gate: verify the provider protocol before large implementation. | covered | — | OQ-1 and §7. No action. |

## docs/evaluation.md

| Finding | Type | Severity | Suggested PRD fix |
|---|---|---|---|
| Metrics left out of FR-23: **planner quality** (delegations, unnecessary revisits, failure to stop, missed roles), **durability**, and **safety** (tool denials, no write attempts, no secret leakage), plus per-Activity latency. | missing | medium | Extend FR-23's metric list, or add SM-6 "Planner quality reported". (T2) |
| Protocol step 4/5: match findings by root cause, location and failure scenario, and adjudicate blindly or consistently against the goldset. | missing | medium | Add FR-23a: one written matching and adjudication rule, applied the same way to both modes (blind where feasible). This connects to OQ-8. (T2) |
| Protocol step 2: record all outputs of every trial. | missing | low | Add to FR-23: every trial's reports and manifests are kept, including PARTIAL and FAILED runs. |
| RATE_LIMIT scenario: bounded classified retry, then fail or partial. | missing FR | high | See T1. |
| Fixture code must compile (or deliberate failures are marked), and it contains no real credentials. | missing | low | Add to FR-22. |
| Tests must distinguish a persisted step completion from an LLM request that may already have been billed. | partially covered | low | Add to FR-20: the report or trace marks retried attempts as possibly billed. |
| External-LLM end-to-end tests are manual/opt-in and not required in CI. Safety and durability tests can use a mocked provider. | missing | low | Add to §8: SM-2 and SM-3 may be shown with a mocked provider; SM-1 needs the real model. |
| Exit criterion: "production-readiness is **not** implied". | missing (honesty) | low | Add to §8: "Passing all metrics does not imply production readiness." |

## docs/assumptions-and-risks.md

| Finding | Type | Severity | Suggested PRD fix |
|---|---|---|---|
| A-01: optimize for *transparent control/evaluation, not scale*. The quality-attribute priority is not stated. | missing (qualitative intent) | low | Add to §1: "Priority order: honesty and transparency, then safety and durability, then quality, then cost and latency. Scale is not a goal." (supports T3) |
| A-16: provider pricing can change; recheck it and log it. Cost estimates need a recorded price basis. | missing | medium | Extend NFR-3 and FR-19: each run records the price table or version used for estimates. Otherwise SM-5 and the SM-1 cost comparisons cannot be reproduced. |
| Reliability DoD: rerun the frozen fixture and compare reproducible summary metrics. | missing | low | Add to NFR-3 or SM-1: reruns on the same pinned inputs give summary metrics within the reported variability. |
| Risk: if the disk is ephemeral, local reports are lost even though the workflow is durable. | missing | low | Add to NFR-6 assumptions: report files live on persistent local storage. |
| A-02 / NFR-4: "explicitly approved" code is allowed, but addendum B16 says v1 is synthetic-only and has no approval path. | contradiction (internal) | medium | Make §2.2 and NFR-4 say "synthetic only in v1", and move "explicitly approved" to v2 or §6. This removes a capability that no requirement backs. |
| Risk: leaked tokens or code in traces. | covered | — | NFR-4. No action. |
| Q6: an opt-in redacted transcript artifact. | covered | — | Out of scope in §7. No action. |
