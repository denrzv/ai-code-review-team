---
title: AI Code Review Team — PRD
status: final
created: 2026-11-21
updated: 2026-11-21
---

# PRD: AI Code Review Team

## 0. Document Purpose

This document defines the product requirements for a solo learning proof of concept (PoC). It says *what* the system must do and how success is judged. Architecture, contracts and technology choices live in `docs/` and in `addendum.md`, not here. Sources: `README.md` and `docs/` at baseline `55169b9`, used as-is.

## 1. Vision

The PoC reviews one pinned Git diff with a small team of LLM agents (Correctness, Security, Performance). An LLM Planner picks the next agent. The PoC produces a report that **never claims more than it can prove**.

The project answers one honest question with evidence: **does a planned multi-agent team find more real defects than a single agent, for comparable cost?** It also shows that a long, LLM-heavy review survives a worker crash without redoing completed work.

Guiding rule: *LLMs suggest; the durable orchestrator controls execution; deterministic code validates claims and enforces boundaries.*

## 2. Target User

**Operator (solo):** the project author. They run reviews against a synthetic repository, read the reports, run evaluation experiments, and learn durable agent orchestration in the process. No other human users exist in v1.

### 2.1 Jobs To Be Done

- **Review a change:** "When I have a commit pair, I want a categorized, source-located review that I can trust not to invent line numbers, so I can judge the findings myself."
- **Measure the team:** "I want to compare single-agent and multi-agent review on a seeded fixture, so I know whether the extra LLM calls earn their cost."
- **Learn durability:** "I want to kill the worker mid-review and see it resume without rerunning finished steps, so I understand what durable orchestration guarantees and what it doesn't."

### 2.2 Non-Users (v1)

- Teams, PR authors, or remote users. There is no multi-user access and no PR integration.
- Anyone reviewing real bank or private code. v1 sends only synthetic code to the external LLM, and no approval path exists.

## 3. Glossary

- **Review run:** one review of one Snapshot, identified by a stable `reviewId`.
- **Snapshot:** an immutable pair of full commit SHAs (base, head) in an allowlisted repository.
- **Fixture:** the synthetic, allowlisted repository used for reviews and evaluation.
- **Planner:** the LLM that decides the next step: *delegate to a specialist* or *complete*.
- **Specialist:** a role-scoped LLM agent (Correctness, Security, Performance) with read-only repository tools. **Single** is the all-category baseline agent.
- **Delegation:** one Planner decision to hand a task to a specialist. **Topic:** the Planner's label for what a delegation investigates, used to limit repeat work on the same area.
- **Invocation:** one execution of a Planner or specialist step. **Attempt:** one try of an invocation. An invocation may need several attempts after crashes or retries.
- **Finding Candidate:** a finding proposed by an LLM. It is untrusted until validated.
- **Evidence status:**
  - `SOURCE_VERIFIED`: file, SHA, line range and quoted snippet match the head.
  - `NEEDS_REVIEW`: the location exists, but the snippet only partly matches or the claim spans beyond the cited range.
  - `INVALID_EVIDENCE`: the location or snippet does not match.

  **`SOURCE_VERIFIED` proves location, not that the bug is real.**
- **Coverage:** the share of changed files, and of changed lines, that at least one agent read through the tools during the run.
- **Budget:** hard limits per review run on delegations, tool calls, tokens, estimated cost and elapsed time.
- **Comparable budget:** the same estimated-cost ceiling per run in both modes. Token totals are reported alongside.
- **PARTIAL:** a terminal state meaning the review stopped early because of a budget or another recoverable limit, after achieving some coverage. It always lists reasons and coverage. Failures end `FAILED` and cancellations end `CANCELLED`.
- **Manifest:** the per-run record of hashes, SHAs, model and prompt versions, price basis and limitations.
- **Goldset:** the hidden list of seeded defects used only for evaluation.

## 4. Features

### 4.1 Review Submission and Lifecycle

**Description:** The operator submits a review asynchronously and polls for status. A review moves through `SUBMITTED → RUNNING → COMPLETED | PARTIAL | FAILED | CANCELLED`. A review that stopped early must never look like a clean one.

#### FR-1: Submit a review
Operator can submit a review for an allowlisted repository, a base SHA, a head SHA and a mode (single-agent or multi-agent). The system responds at once with an accepted status, a review identifier and a location to poll, without waiting for the review to finish.
- Unknown repository, unknown SHA, or uncommitted-only changes are rejected with a clear error before any LLM call.
- A diff over the size limits (changed files or lines) is rejected at submission with a reason.

#### FR-2: Idempotent review identity
Operator can supply a `reviewId`, or let the system generate one.
- Resubmitting the same `reviewId` with the same payload returns the existing run. It does not start a second one.
- Resubmitting the same `reviewId` with a *different* payload is rejected as a conflict. *(Decision: the docs leave conflict semantics undefined; this PRD defines them.)*

#### FR-3: Status and progress
Operator can query a run's state, its progress (delegations done, budget used) and links to its reports.

#### FR-4: Cancel
Operator can cancel a running review.
- Cancellation stops further LLM calls within a bounded time, and the run ends `CANCELLED`, never `COMPLETED`.

#### FR-5: Honest terminal state
- Every `PARTIAL`, `FAILED` and `CANCELLED` run carries machine-readable reasons and the coverage achieved.
- The system never reports "no issues found" for a run that did not cover the whole diff.

### 4.2 Pinned, Read-Only Repository Access

**Description:** Agents can see the code under review through a small, bounded, read-only toolset tied to one Snapshot. They can never change anything.

#### FR-6: Pinned snapshot
Every tool call in a review reads content at the run's pinned SHAs.
- Moving a branch or adding commits after submission does not change what the run reads.

#### FR-7: Read-only review tools
Specialists can list changed files, read the diff, read a bounded line range of a file, search code, find related tests, and read build metadata.
- No tool can write, check out, run shell commands, execute project code or tests, or reach the network.
- Every tool result is size-bounded (for example, a file read returns at most 200 lines).

#### FR-8: Path and content policy
- Path traversal (`..`), symlink escapes, out-of-policy submodules, binary files and oversized content are refused.
- Secrets in tool output are redacted before they reach the LLM or the logs.
- The goldset is never readable through any tool or included in any prompt. *(Decision: the mechanism, such as a separate location or an exclusion rule, is left to the architecture.)*

### 4.3 Planned Multi-Agent Review

**Description:** In multi-agent mode, the Planner repeatedly chooses a specialist and a focused task until it decides the review is complete or a budget runs out. The Planner only *proposes*. The orchestrator validates every decision and enforces every limit.

#### FR-9: Planner decisions
The Planner returns either *delegate (role, task, topic, rationale)* or *complete*.
- Decisions naming an unknown role, or trying to change budgets or call tools directly, are rejected and recorded. They are never executed.

#### FR-10: Specialist review
Each delegated specialist investigates its task with the read-only tools and returns finding candidates, open questions, a short summary of work done, and usage.
- Each role's instructions are versioned, and the version is recorded in the report.

#### FR-11: Budgets and stopping
The system enforces per-run limits on delegations, invocations per topic, tool calls per invocation, input and output tokens, **estimated cost**, and elapsed time.
- Exhausting any limit ends the run `PARTIAL`, with that limit named as the reason.
- Limits are enforced in code, never only in prompts. *(Decision: a cost limit is required by project rules but is missing from the documented budget fields. It is added here.)*

#### FR-12: Bounded shared context
Later Planner and specialist calls receive a bounded summary of earlier work, not full transcripts.

### 4.4 Single-Agent Baseline

#### FR-13: Single-agent mode
Operator can run the same review in single-agent mode. One agent covers all categories with the same model, Snapshot, tools, evidence rules and a comparable budget. No Planner is involved. *(Decision: no planned feature owns this mode yet. It must exist before the evaluation.)*

### 4.5 Finding Validation

**Description:** Everything an LLM claims is checked by deterministic code before it appears as a finding.

#### FR-14: Evidence check
Each candidate's file, SHA, line range and quoted snippet are checked against the head of the Snapshot, and the candidate receives an evidence status.
- A candidate with a fabricated or shifted line reference is marked `INVALID_EVIDENCE` and is never shown as a finding.
- A `NEEDS_REVIEW` candidate appears only in the report's unverified section, never as a finding.

#### FR-15: Keep the audit trail
Rejected and unverified candidates are kept, separately from verified findings, and can be inspected in the report. *(Decision: the report currently has no place for rejected candidates. One is added.)*

#### FR-16: Deduplication
Duplicate candidates (same normalized issue and location) are merged, and the merged finding keeps the provenance of every agent that raised it.

#### FR-17: Malformed model output
Malformed structured output from the Planner or a specialist gets at most one repair attempt. After that it is rejected and recorded, and it never crashes the run.

### 4.6 Reports

#### FR-18: JSON and Markdown reports
Each `COMPLETED` or `PARTIAL` run produces a versioned JSON report, a human-readable Markdown report, and a manifest (see §3).
- Reports contain categorized findings with source locations, evidence status, coverage, limitations, usage and cost.
- Severity is labeled as model-assigned, not as proven impact.
- `FAILED` and `CANCELLED` runs still produce a minimal report with reasons, coverage and usage.
- Rewriting a report after a retry is safe and yields identical content for identical inputs.

#### FR-19: Usage and cost accounting
Reports show tokens and estimated cost per agent invocation and in total. Actual provider usage is shown where available, and estimates are labeled as estimates.
- Retried attempts are counted and disclosed. The report never claims exactly-once billing.
- The price basis used for estimates is recorded in the manifest, so cost figures can be reproduced.

#### FR-25: Decision trail
Operator can inspect, in the report, why the run went the way it did: every Planner decision (role, task, topic, rationale), every rejected Planner decision, and the reason each candidate was rejected or left unverified.

### 4.7 Durable Recovery

#### FR-20: Resume after crash
If the worker process dies mid-review, the run resumes when a worker returns.
- Completed steps (Planner decisions, finished specialist invocations) are not re-executed.
- The interrupted step may run again, and that retry is visible in the report.

#### FR-21: Replay without extra calls
Recovering a run reuses recorded Planner decisions and makes no extra Planner LLM calls for steps that already completed.

#### FR-26: Provider errors and rate limits
When the LLM provider rate-limits a call or returns a transient server error, the system retries with backoff up to a fixed limit.
- Permanent request errors (malformed request, content-policy refusal) are not retried.
- Retries are not stacked: the client-level and orchestration-level retry layers do not multiply each other.
- When retries are exhausted, the run ends `PARTIAL` (if coverage was achieved) or `FAILED`, with the provider error named as the reason.

### 4.8 Evaluation Harness

#### FR-22: Seeded fixture with hidden goldset
A synthetic Java/Spring repository contains 9 seeded defects and 1 non-defect control. A separate goldset describes each defect by root cause, location and failure scenario.

#### FR-23: Comparative experiment
Operator can run single-agent and multi-agent modes 3–5 times each on the fixture, with pinned model parameters and a comparable total budget. The experiment produces recall, precision, false positives per run, coverage, latency, cost, Planner decision quality, and partial or failed run counts, with averages and variability.
- **Matching rule:** a finding matches a seeded defect when its root cause and location agree with the goldset and its failure scenario is plausible.
  - Recall is matched defects divided by 9.
  - Precision is semantically valid findings divided by findings shown.
  - The same rule is applied to both modes, judged blind to the mode where possible.
- Every trial's reports and manifest are kept.

#### FR-24: Publish results regardless
The comparison and its cost/quality frontier are written up **even if multi-agent underperforms**. An inconclusive result, which is likely with 9 defects and 3–5 trials, is a valid outcome and is reported as inconclusive.

## 5. Cross-Cutting Non-Functional Requirements

- **NFR-1 Security:** zero write, shell, network or code-execution capability anywhere in the agent toolset. All source content, diffs and LLM output are treated as untrusted. Prompt injection in the source cannot change tool policy.
- **NFR-2 Durability boundary:** durability is guaranteed per completed step, not per LLM or tool call. Exactly-once LLM billing or tool execution is never claimed. The orchestrator's storage must survive a worker restart. If that storage is lost, durability guarantees are void.
- **NFR-3 Reproducibility:** every run records the inputs needed to rerun it: SHAs, model ID, prompt versions, budget and fixture ID. Report assembly is deterministic.
- **NFR-4 Privacy:** only synthetic code is sent to the external LLM provider in v1. Logs are minimal and redacted, and API keys are never committed or logged.
- **NFR-5 Bounded spend:** each run has a hard estimated-cost ceiling. *(Decision: default ≤ USD 0.50 per review run on the fixture. Revisit after the model smoke test.)*
- **NFR-6 No application database:** run lifecycle state lives only in the durable orchestrator. Results live in versioned files.
- **NFR-7 Observability:** each LLM invocation is traceable by run, invocation and attempt, with model, prompt version, tokens and cost.

## 6. Non-Goals (Explicit)

- No production deployment, scale, multi-tenancy, or access control for remote users.
- No remote, proprietary or bank repositories.
- No repository mutation, auto-fix, PR creation or PR comments.
- No shell, no running the project's code or tests, no web search.
- No MCP, RAG or vector DB, and no long-term agent memory.
- No per-LLM-call checkpointing (possible v2).
- No claim that a finding is a real bug. Semantic judgment stays with the operator.
- No citations of deleted-only (base-side) code in v1.

## 7. MVP Scope

**In scope:** everything in §4. The delivery order (tech spike → first vertical slice → planned team → reports and durable demo → evaluation) follows `docs/implementation-plan.md`.

**Out of scope for MVP:** §6, plus an opt-in redacted transcript debug artifact, object storage for reports, and large-repository indexing.

## 8. Success Metrics

The project succeeds when the operator can show, with reproducible evidence, what the team does and doesn't buy, and when every safety and durability test passes. "Multi-agent wins" is **not** a success criterion.

**Primary**
- **SM-1 Comparison published:** single-agent vs multi-agent, 3–5 trials each, with recall, precision, false positives per run, cost and latency, including runs where multi-agent loses. Validates FR-13, FR-22–FR-25.
- **SM-2 Durability demonstrated:** all durability scenarios pass: worker restart, Planner replay, duplicate report write, rate limit, bad schema, fake line, budget exceeded, cancel. Validates FR-4, FR-11, FR-14, FR-17, FR-18, FR-20, FR-21, FR-26.
- **SM-3 Safety holds:** traversal, symlink escape and prompt-injection fixtures are all contained, with zero write or shell capability present. Validates FR-7, FR-8, NFR-1.

**Secondary**
- **SM-4 Evidence integrity:** 0 findings with fabricated locations are shown as `SOURCE_VERIFIED`. Validates FR-14.
- **SM-5 Spend:** no run exceeds its cost ceiling. Validates FR-11, NFR-5.

**Counter-metrics (do not optimize)**
- **SM-C1 Finding count:** more findings is not better. Raw volume inflates noise. Counterbalances SM-1 recall.
- **SM-C2 `SOURCE_VERIFIED` rate:** a correct location does not make a real bug, so precision is judged semantically against the goldset. Counterbalances SM-4.
- **SM-C3 Delegation count:** more agent calls do not mean more thoroughness. Counterbalances SM-1.

## 9. Open Questions

- **OQ-1:** Does the chosen model reliably produce structured Planner output and multi-step tool calls through the chosen integration? (A validation spike is required before orchestration.)
- **OQ-2:** How should permanent bad-request errors be told apart from rate limits and transient failures for retries?
- **OQ-3:** Is read-only blob search good enough for "find related tests" and "search code"?
- **OQ-4:** How are report writes reconciled when a write succeeds but its acknowledgment is lost?
- **OQ-5:** Should the fixture include hard-to-verify cases (cross-file defects, a near-miss control)?
- **OQ-6:** How should the elapsed-time budget be enforced, given that orchestration logic cannot read the wall clock directly? (For the architecture.)
- **OQ-7:** Early slices need a small synthetic repository before the full seeded fixture exists. Who provides it? (For epics.)

## 10. Confirmed Decisions (formerly assumptions)

- §4.1 FR-2: the same `reviewId` with a different payload is rejected as a conflict.
- §4.2 FR-8: goldset isolation is enforced by a mechanism the architecture chooses.
- §4.3 FR-11: an estimated-cost limit is part of the budget.
- §4.4 FR-13: single-agent mode is a first-class mode that needs an owning feature.
- §4.5 FR-15: the report gains a section for rejected candidates.
- §5 NFR-5: the default cost ceiling is ≤ USD 0.50 per run on the fixture.
