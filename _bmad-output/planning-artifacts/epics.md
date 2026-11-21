---
status: final
stepsCompleted: [step-01-validate-prerequisites, step-02-design-epics, step-03-create-stories, step-04-final-validation]
inputDocuments:
  - _bmad-output/planning-artifacts/prds/prd-ai-code-review-team-2026-11-21/prd.md
  - _bmad-output/planning-artifacts/architecture/architecture-ai-code-review-team-2026-11-21/ARCHITECTURE-SPINE.md
  - _bmad-output/planning-artifacts/prds/prd-ai-code-review-team-2026-11-21/addendum.md
  - docs/contracts.md
  - docs/implementation-plan.md
  - docs/evaluation.md
---

# ai-code-review-team - Epic Breakdown

## Overview

This document provides the complete epic and story breakdown for ai-code-review-team, decomposing the requirements from the PRD, UX Design if it exists, and Architecture requirements into implementable stories.

## Requirements Inventory

### Functional Requirements

**FR-1: Submit a review**

Operator can submit a review for an allowlisted repository, a base SHA, a head SHA and a mode (single-agent or multi-agent). The system responds at once with an accepted status, a review identifier and a location to poll, without waiting for the review to finish.
- Unknown repository, unknown SHA, or uncommitted-only changes are rejected with a clear error before any LLM call.
- A diff over the size limits (changed files or lines) is rejected at submission with a reason.

**FR-2: Idempotent review identity**

Operator can supply a `reviewId`, or let the system generate one.
- While review history or a verified publication manifest identifies the existing run, resubmitting the same `reviewId` with the same payload returns that run. A different payload is rejected as a conflict.
- An unpublished `reviewId` can be reused after its review history expires. A new run can start only after the system confirms the absence of both history and a publication record.

**FR-3: Status and progress**

Operator can query a run's state, its progress (delegations done, budget used) and links to its reports.

**FR-4: Cancel**

Operator can cancel a running review.
- Cancellation stops further LLM calls within a bounded time, and the run ends `CANCELLED`, never `COMPLETED`.

**FR-5: Honest terminal state**

- Every `PARTIAL`, `FAILED` and `CANCELLED` run carries machine-readable reasons and the coverage achieved.
- The system never reports "no issues found" for a run that did not cover the whole diff.

**FR-6: Pinned snapshot**

Every tool call in a review reads content at the run's pinned SHAs.
- Moving a branch or adding commits after submission does not change what the run reads.

**FR-7: Read-only review tools**

Specialists can list changed files, read the diff, read a bounded line range of a file, search code, find related tests, and read build metadata.
- No tool can write, check out, run shell commands, execute project code or tests, or reach the network.
- Every tool result is size-bounded (for example, a file read returns at most 200 lines).

**FR-8: Path and content policy**

- Path traversal (`..`), symlink escapes, out-of-policy submodules, binary files and oversized content are refused.
- Secrets in tool output are redacted before they reach the LLM or the logs.
- The goldset is never readable through any tool or included in any prompt. *(Decision: the mechanism, such as a separate location or an exclusion rule, is left to the architecture.)*

**FR-9: Planner decisions**

The Planner returns either *delegate (role, task, topic, rationale)* or *complete*.
- Decisions naming an unknown role, or trying to change budgets or call tools directly, are rejected and recorded. They are never executed.

**FR-10: Specialist review**

Each delegated specialist investigates its task with the read-only tools and returns finding candidates, open questions, a short summary of work done, and usage.
- Each role's instructions are versioned, and the version is recorded in the report.

**FR-11: Budgets and stopping**

The system enforces per-run limits on delegations, invocations per topic, tool calls per invocation, input and output tokens, **estimated cost**, and elapsed time.
- A run that stops because it exhausted a budget ends `PARTIAL` if it observed at least one changed unit, including a descriptor-only change, and `FAILED` if it observed none. Both outcomes name the exhausted limit.
- Limits are enforced in code, never only in prompts. *(Decision: a cost limit is required by project rules but is missing from the documented budget fields. It is added here.)*

**FR-12: Bounded shared context**

Later Planner and specialist calls receive a bounded summary of earlier work, not full transcripts.

**FR-13: Single-agent mode**

Operator can run the same review in single-agent mode. One agent covers all categories with the same model, Snapshot, tools, evidence rules and a comparable budget. No Planner is involved. *(Decision: no planned feature owns this mode yet. It must exist before the evaluation.)*

**FR-14: Evidence check**

Each candidate's file, SHA, line range and quoted snippet are checked against the head of the Snapshot, and the candidate receives an evidence status.
- A candidate with a fabricated or shifted line reference is marked `INVALID_EVIDENCE` and is never shown as a finding.
- A `NEEDS_REVIEW` candidate appears only in the report's unverified section, never as a finding.

**FR-15: Keep the audit trail**

Rejected and unverified candidates are kept, separately from verified findings, and can be inspected in the report. *(Decision: the report currently has no place for rejected candidates. One is added.)*

**FR-16: Deduplication**

Duplicate candidates (same normalized issue and location) are merged, and the merged finding keeps the provenance of every agent that raised it.

**FR-17: Malformed model output**

Malformed structured output from the Planner or a specialist gets at most one repair attempt. After that it is rejected and recorded, and it never crashes the run.

**FR-18: JSON and Markdown reports**

Each `COMPLETED` or `PARTIAL` run produces a versioned JSON report, a human-readable Markdown report, and a manifest (see §3).
- Reports contain categorized findings with source locations, evidence status, coverage, limitations, usage and cost.
- Severity is labeled as model-assigned, not as proven impact.
- `FAILED` and `CANCELLED` runs still produce a minimal report with reasons, coverage and usage.
- Rewriting a report after a retry is safe and yields identical content for identical inputs.

**FR-19: Usage and cost accounting**

Reports show tokens and estimated cost per agent invocation and in total. Actual provider usage is shown where available, and estimates are labeled as estimates.
- Retried attempts are counted and disclosed. The report never claims exactly-once billing.
- The price basis used for estimates is recorded in the manifest, so cost figures can be reproduced.

**FR-20: Resume after crash**

If the worker process dies mid-review, the run resumes when a worker returns.
- Completed steps (Planner decisions, finished specialist invocations) are not re-executed.
- The interrupted step may run again, and that retry is visible in the report.

**FR-21: Replay without extra calls**

Recovering a run reuses recorded Planner decisions and makes no extra Planner LLM calls for steps that already completed.

**FR-22: Seeded fixture with hidden goldset**

A synthetic Java/Spring repository contains 9 seeded defects and 1 non-defect control. A separate goldset describes each defect by root cause, location and failure scenario.

**FR-23: Comparative experiment**

Operator can run single-agent and multi-agent modes 3–5 times each on the fixture, with pinned model parameters and a comparable total budget. The experiment produces recall, precision, false positives per run, coverage, latency, cost, Planner decision quality, and partial or failed run counts, with averages and variability.
- **Matching rule:** a finding matches a seeded defect when its root cause and location agree with the goldset and its failure scenario is plausible.
  - Recall is matched defects divided by 9.
  - Precision is semantically valid findings divided by findings shown.
  - The same rule is applied to both modes, judged blind to the mode where possible.
- Every trial's reports and manifest are kept.

**FR-24: Publish results regardless**

The comparison and its cost/quality frontier are written up **even if multi-agent underperforms**. An inconclusive result, which is likely with 9 defects and 3–5 trials, is a valid outcome and is reported as inconclusive.

**FR-25: Decision trail**

Operator can inspect, in the report, why the run went the way it did: every Planner decision (role, task, topic, rationale), every rejected Planner decision, and the reason each candidate was rejected or left unverified.

**FR-26: Provider errors and rate limits**

When the LLM provider rate-limits a call or returns a transient server error, the system retries with backoff up to a fixed limit.
- Permanent request errors (malformed request, content-policy refusal) are not retried.
- Retries are not stacked: the client-level and orchestration-level retry layers do not multiply each other.
- When retries are exhausted, the run ends `PARTIAL` (if coverage was achieved) or `FAILED`, with the provider error named as the reason.

### NonFunctional Requirements

- **NFR-1 Security:** zero write, shell, network or code-execution capability anywhere in the agent toolset. All source content, diffs and LLM output are treated as untrusted. Prompt injection in the source cannot change tool policy.
- **NFR-2 Durability boundary:** durability is guaranteed per completed step, not per LLM or tool call. Exactly-once LLM billing or tool execution is never claimed. The orchestrator's storage must survive a worker restart. If that storage is lost, durability guarantees are void.
- **NFR-3 Reproducibility:** every run records the inputs needed to rerun it: SHAs, model ID, prompt versions, budget and fixture ID. Report assembly is deterministic.
- **NFR-4 Privacy:** only synthetic code is sent to the external LLM provider in v1. Logs are minimal and redacted, and API keys are never committed or logged.
- **NFR-5 Bounded spend:** each run has a hard estimated-cost ceiling. *(Decision: default ≤ USD 0.50 per review run on the fixture. Revisit after the model smoke test.)*
- **NFR-6 No application database:** run lifecycle state lives only in the durable orchestrator. Results live in versioned files.
- **NFR-7 Observability:** each LLM invocation is traceable by run, invocation and attempt, with model, prompt version, tokens and cost.

### Additional Requirements

Use the final PRD for product scope and the final Architecture Spine for implementation decisions. The supporting documents supply baseline contracts, FEATURE sequencing and test scenarios. The spine resolves earlier sketches on Activity names, tool names, report reasons and artifact paths, goldset isolation and the single-agent baseline. Keep provisional values from the spine traceable to their decision IDs.

- **AD-1 — Project bootstrap.** The Structural Seed specifies one Java 25/Spring Boot Maven module, the package layout, handwritten Temporal SDK wiring, typed configuration and CI with fake models. Resolve the specified dependencies and run `mvn verify` on Java 25. Enforce hexagonal package boundaries in the build and keep the domain JDK-only.
- **AD-2 — Durable orchestration.** Use one Temporal Workflow per review, deterministic IDs, Workflow time and timers. Keep model, Git, network and filesystem effects in Activities or API preflight. Use persistent local Temporal and keep lifecycle state there.
- **AD-3 — Activity contracts and preflight.** Schedule `planNext`, `reviewSpecialist` and `validateAndReport`. Preflight checks the synthetic allowlist, reachable full SHAs, BASE ancestry and a nonempty bounded diff. Freeze policy, budget and canonical change units. Bound report attempts and deadlines, with transient filesystem retry and permanent failure on conflicting or invalid input.
- **AD-4 — Shared baseline.** Run exactly one `SINGLE` specialist invocation with the same Workflow, run ceilings, tool policy, validator and reports. Skip the Planner for this mode and exclude `SINGLE` from plannable roles.
- **AD-5 — Planner policy.** Validate actions, roles and fields in the Workflow. Normalize topics with NFKC, root-locale lowercase and ASCII hyphens, capped at 64 characters. Keep ordered dispositions. Three consecutive invalid decisions stop with `PLANNER_INVALID`. Budget refusals retain a specific reason, and an eligible Planner may still return COMPLETE after the last delegation.
- **AD-6 — Output repair.** Return typed invalid/rejected outcomes after at most one budgeted repair request. Malformed structured output takes the recorded rejection path.
- **AD-7 — Provider retries.** Disable client and transport retries. Classify transient and permanent failures in the adapter and apply up to three work Activity attempts with bounded 2–30 s exponential backoff and timeouts clamped to the remaining run budget. Preserve attempts and measured versus unknown usage when retries exhaust.
- **AD-8 — Spend and execution budgets.** Freeze model settings and dated pricing, reserve token, tool and estimated cost allocations in integer microdollars and split them across attempts. Check deadline, projected input/output, cost and tool dispatch in Java, including repair requests. Account conservatively for unknown attempts and release provably unused shares. Verify default allocations fit the 15-minute, USD 0.50 ceiling, six delegations, two per topic, 30 run tool calls and 12 specialist tool calls per invocation. Use a deterministic deadline timer and a five-minute server backstop.
- **AD-9 — Snapshot tool policy.** Bind per-invocation tools to full 40-hex SHAs, read committed blobs through JGit and route every result through the same canonicalization, denial, redaction and size policy. Use the six canonical tool names from the spine, one-based inclusive lines and position-preserving redaction. Cap reads at 200 lines, search at 20 hits, diffs at 400 lines per file, results at 16,000 Unicode code points and submission at 20 files/1,500 changed lines.
- **AD-10 — Goldset isolation.** Keep evaluation data physically outside all reviewable Git repositories, restrict reads to the evaluation package and deny `**/goldset*` in tool policy.
- **AD-11 — Evidence and deduplication.** Validate once inside report finalization against policy-checked HEAD. Require canonical paths and valid bounds, exact normalized contiguous quotes for verification and the spine's partial-match threshold for unverified evidence. Classify shifted quotes as invalid. Require every supplied reference to verify before showing a finding. Label changed/context evidence and preserve dispositions, provenance and stable ordering.
- **AD-12 — Serialization and context.** Test resolved Temporal and Spring Boot Jackson mappings separately. Use discriminated records, sanitized quotes, at most 20 candidates per invocation, 15 lines/1,024 UTF-8 bytes per quote, a 4,000-code-point ledger and a tested 512 KiB worst-case report input.
- **AD-13 — Artifact publication.** Publish schema-versioned JSON, Markdown and a manifest through one same-filesystem hard-link commit point from immutable attempt output. Test concurrent/retried writes and verify frozen input and report digests. Expose links only for a complete matching publication. Keep publication errors distinct from the frozen review disposition. Apply the spine's running, unavailable and unknown-ID report responses.
- **AD-14 — REST identity and status.** Hash canonical requests with a versioned encoding, store the hash in Temporal memo and manifest and start only on confirmed absence. Return 202 for new, 200 for matching and 409 for conflicting submissions. Query active runs, read closed results and use verified manifests after history expiry. Verify 30-day development retention and document reuse of unpublished IDs after expiry.
- **AD-15 — Coverage and terminal reasons.** Union only change units actually delivered after policy. Count additions on HEAD, deletions on BASE and complete descriptor-only changes. Report changed, read and fully covered files, line coverage, and attempted/completed roles using the same definitions across status, artifacts and evaluation. Use structured reasons and disclose unknown or denied coverage. Permit the clean-result wording only on a complete, fully covered run with no findings or unverified candidates.
- **AD-16 — Bounded cancellation.** Accept cancellation via Workflow Update before disposition freeze. Heartbeat during blocking work, bound the provider call and acknowledgment wait, account for unresolved usage and finalize in a detached cancellation scope. Exercise cancellation during a blocked call and after finalization begins.
- **AD-17 — Replay and recovery.** Capture single-agent and multi-agent histories at FEATURE-006 and FEATURE-010. Version later Workflow command sequence changes, replay the recorded histories and test worker kill/restart on persistent Temporal using independent provider-call counts. Cover duplicate publication, rate limits, malformed output, false references, budget stops and cancellation.
- **AD-18 — Adapter privacy and observability.** Version prompts and freeze their version map at submission. Enforce synthetic-only inputs at the adapter gate, keep provider built-in tools disabled, report provider usage and estimates separately, and use correlated run/invocation/attempt logs without raw source, transcripts or secrets.
- **AD-19 — Reproducible fixture repositories.** Generate the tiny early fixture and full evaluation fixture from committed inputs with deterministic commits and reachable BASE/HEAD tags.
- **AD-20 — Public-contract evaluation.** Run 3–5 paired trials via REST with unique review IDs, pinned settings and comparable run budgets. Retain artifacts for all trials. Score with the PRD matching rule and mode hidden where possible. Publish aggregate metrics, variability, decision quality, cost/quality frontier and first-attempt allowances even for negative or inconclusive outcomes.

#### Items to carry into story design

- Use FEATURE-006 for the all-category single-agent baseline required by FR-13. The roadmap's older Correctness-only description leaves the baseline without an owner.
- Plan for an early reproducible synthetic fixture by FEATURE-004 and the full nine-defect fixture plus control at FEATURE-013. Exact fixture cases and goldset schema remain open.
- Keep the FEATURE-002 wire smoke test for structured output, tool calls, thinking-off behavior and error classification. Keep search and related-test heuristics open for FEATURE-004.
- Use the union of explicit roadmap prerequisites when the diagram and table differ. FEATURE-011 uses FEATURE-005 and FEATURE-010, FEATURE-012 uses FEATURE-003, FEATURE-010 and FEATURE-011, and FEATURE-014 uses FEATURE-011, FEATURE-012 and FEATURE-013. The story dependencies below express that order.
- Carry the PRD success checks into the acceptance criteria: publish the comparison (SM-1), demonstrate recovery and failure handling (SM-2), contain unsafe tool requests (SM-3), show zero fabricated source references as verified (SM-4), and enforce the spend ceiling (SM-5).

### UX Design Requirements

The v1 operator interacts through REST and reads JSON and Markdown artifacts. The source set contains no separate UX design contract.

### FR Coverage Map

- FR-1 → Epic 2 · Submit a review
- FR-2 → Epic 2 · Idempotent review identity
- FR-3 → Epic 2 · Status and progress
- FR-4 → Epic 2 · Cancel
- FR-5 → Epic 2 · Honest terminal state
- FR-6 → Epic 1 · Pinned snapshot
- FR-7 → Epic 1 · Read-only review tools
- FR-8 → Epic 1 · Path and content policy
- FR-9 → Epic 2 · Planner decisions
- FR-10 → Epic 2 · Specialist review
- FR-11 → Epic 2 · Budgets and stopping
- FR-12 → Epic 2 · Bounded shared context
- FR-13 → Epic 1 · Single-agent mode
- FR-14 → Epic 1 · Evidence check
- FR-15 → Epic 1 · Keep the audit trail
- FR-16 → Epic 1 · Deduplication
- FR-17 → Epic 1 · Malformed model output
- FR-18 → Epic 2 · JSON and Markdown reports
- FR-19 → Epic 2 · Usage and cost accounting
- FR-20 → Epic 2 · Resume after crash
- FR-21 → Epic 2 · Replay without extra calls
- FR-22 → Epic 3 · Seeded fixture with hidden goldset
- FR-23 → Epic 3 · Comparative experiment
- FR-24 → Epic 3 · Publish results regardless
- FR-25 → Epic 2 · Decision trail
- FR-26 → Epic 2 · Provider errors and rate limits

## Epic List

### Epic 1: Review a pinned change with one agent

Run a single-agent review of a small synthetic Git change through local Temporal and inspect the source evidence behind its findings.

**FRs covered:** FR-6, FR-7, FR-8, FR-13, FR-14, FR-15, FR-16, FR-17

**Roadmap:** FEATURE-001–006. Verify the Java build, provider protocol and persistent Temporal before wiring the vertical slice. Use the early reproducible fixture, the shared read-only and privacy policies, a bounded provider invocation and the shared evidence validator. Capture a single-agent replay history. The local entry point and inspectable result make this slice runnable before the public REST lifecycle ships.

### Epic 2: Run and recover an auditable team review

Submit, track and cancel a planned team review through REST, inspect its decisions, coverage and costs in published reports, and demonstrate that it recovers after a worker restart.

**FRs covered:** FR-1, FR-2, FR-3, FR-4, FR-5, FR-9, FR-10, FR-11, FR-12, FR-18, FR-19, FR-20, FR-21, FR-25, FR-26

**Roadmap:** FEATURE-007–012. Build on the first epic’s Workflow and validation path. Add the Planner, role-scoped specialists, shared run budgets, stable submission identity, deterministic artifact publication, and the recovery demonstration. The public API supports both review modes.

### Epic 3: Measure what the specialist team adds

Run both review modes on the same seeded change under comparable budgets and publish a reproducible comparison of quality, latency and cost.

**FRs covered:** FR-22, FR-23, FR-24

**Roadmap:** FEATURE-013–014. The fixture can be built once FEATURE-004 is available. Comparative trials use the public API, retained artifacts and verified recovery behavior from Epic 2, with ground truth isolated from agent tools.

## Epic 1: Review a pinned change with one agent

Run a single-agent review of a small synthetic Git change through local Temporal and inspect the source evidence behind its findings.

### Story 1.1: Verify the local Java 25 build

As the operator,
I want a reproducible local build and automated architecture checks,
So that I can validate the review service before connecting it to a model provider or a Temporal worker.

**Traceability:** FEATURE-001 · AD-1 and Architecture Spine Stack and Structural Seed · Supports NFR-3, NFR-4 and NFR-6.

**Dependencies:** None.

**Acceptance Criteria:**

**Given** a clean checkout with Java 25 and the Maven version specified in the Architecture Spine

**When** the operator or CI runs `mvn verify`

**Then** the single Spring Boot Maven module compiles and its verification suite passes against the resolved dependency versions

**And** the suite runs without a provider key, an external Temporal server, or an application database.

**Given** the package dependency rules in AD-1

**When** the architecture checks encounter a domain dependency outside the JDK or a Workflow implementation that imports an adapter

**Then** `mvn verify` fails and identifies the violating dependency.

**Given** the documented local configuration and CI environment

**When** the operator follows the build instructions

**Then** the instructions identify the Java and Maven prerequisites and verification command, and any example provider key is an environment-variable placeholder.

### Story 1.2: Verify the DeepSeek review protocol

As the operator,
I want to verify the provider's structured responses and read-only tool calls,
So that I can confirm the integration works before running a review through Temporal.

**Traceability:** FEATURE-002 · FR-17 · FR-26 (adapter classification) · AD-6, AD-7, AD-18 · NFR-4.

**Dependencies:** Story 1.1.

**Acceptance Criteria:**

**Given** a local HTTP stub and a synthetic input

**When** the resolved LangChain4j adapter makes a Planner or reviewer request

**Then** the wire test verifies the configured model, thinking-off behavior, output-token bound and Planner JSON object mode

**And** a scripted sequence completes two successive calls to a bounded read-only test tool with provider built-in tools disabled.

**Given** a response that is blank, truncated, or fails local schema validation

**When** the adapter processes it

**Then** the repair path sends at most one additional request if budget permits and returns a typed invalid or rejected outcome if the response still fails validation.

**Given** provider responses for rate limits, transient server or transport failures, invalid or unauthorized requests, and policy refusals

**When** the adapter classifies them

**Then** it identifies the retryable and permanent cases for the Temporal Activity and the client and transport each perform zero automatic retries.

**Given** a non-synthetic input or unset credentials for an optional live smoke test

**When** the provider path is invoked

**Then** non-synthetic inputs are blocked before any external request and a live check requires explicit opt-in with credentials supplied through the environment

**And** CI verifies the protocol with the local stub and records the wire assumptions the optional live check can confirm.

### Story 1.3: Run a Workflow on local persistent Temporal

As the operator,
I want to start and inspect a minimal review Workflow on a local persistent Temporal service,
So that I can verify the worker and persistence setup before running model-backed reviews.

**Traceability:** FEATURE-003 · AD-1, AD-2, AD-3, AD-12, AD-14 · Supports NFR-2 and NFR-6.
**Dependencies:** Story 1.1.

**Acceptance Criteria:**

**Given** the local Temporal dev server starts with the documented database filename and verified 30-day development namespace retention
**When** the application starts its SDK-configured worker and a development smoke command submits a minimal review
**Then** the worker executes the single review Workflow type under `review-{reviewId}`, runs a deterministic smoke path using a fake Activity and exposes its progress and result without a provider key.

**Given** the smoke run has completed and the Temporal database file is retained
**When** the operator stops and restarts the local Temporal service and worker
**Then** describing the same Workflow ID returns its closed execution and the saved result remains retrievable.

**Given** the in-process Temporal test environment and the resolved SDK payload converter
**When** CI runs the smoke Workflow and round-trips its typed input and result
**Then** progress and completion assertions pass and the Workflow implementation depends only on the domain, Activity interfaces and the Temporal Workflow API.

### Story 1.4: Inspect a reproducible pinned Git change

As the operator,  
I want the reviewer to read an allowlisted synthetic commit pair through bounded tools,  
So that later Git changes and untrusted source cannot alter what is reviewed.

**Traceability:** FEATURE-004 · FR-1, FR-6–8 · NFR-1, NFR-3, NFR-4 · SM-3 · AD-3, AD-9, AD-10, AD-15, AD-19.  
**Dependencies:** Story 1.1.

**Acceptance Criteria:**

**Given** two independent runs of the tiny fixture generator  
**When** each generates a Git repository from committed fixture inputs with fixed Git metadata  
**Then** they produce the same reachable BASE and HEAD SHAs and a nonempty test diff.

**Given** a submission preflight  
**When** it receives an unknown or non-synthetic repository, abbreviated or unreachable SHA, a BASE outside HEAD ancestry, an empty committed diff, or a diff over 20 files or 1,500 changed lines  
**Then** it rejects the request before model work with a stable reason.  
**And** for valid input it freezes canonical change identities, rename information, side-specific line ranges and descriptor units for later coverage calculation.

**Given** a valid snapshot and adversarial fixture inputs  
**When** an invocation lists changed files, reads diff or source, searches code, finds related tests, or gets build metadata after the working tree or branch changes  
**Then** all six operations still return results from the same pinned commits using the canonical names in AD-9.  
**And** tests verify one-based inclusive positions, the 200-line read, 20-hit search, 400-line per-file diff, and 16,000-code-point result limits.  
**And** the tools refuse traversal, symlinks, out-of-policy submodules, binaries, oversized content and goldset paths, redact secrets while preserving line positions, and expose only read operations. A source instruction cannot change the policy.

### Story 1.5: Verify candidate evidence against pinned HEAD

As the operator,  
I want every candidate checked against the source and duplicate findings linked to their origins,  
So that I can inspect the evidence and make my own judgment about each claim.

**Traceability:** FEATURE-005 · FR-14–16 · SM-4 · AD-11.  
**Dependencies:** Story 1.4.

**Acceptance Criteria:**

**Given** candidates citing policy-checked HEAD source  
**When** the deterministic validator compares normalized lines in the cited ranges  
**Then** an exact contiguous match within a valid range yields `SOURCE_VERIFIED`, and the AD-11 in-range partial-match case yields `NEEDS_REVIEW`.  
**And** a shifted or fabricated quote, non-HEAD SHA, deleted-only reference, denied path or invalid range yields `INVALID_EVIDENCE`.

**Given** a mixed batch with missing, invalid, unverified and verified references  
**When** the validator classifies the candidates  
**Then** a candidate needs at least one nonblank reference and every reference must verify to appear in findings.  
**And** the result preserves rejected and unverified dispositions with reasons, distinguishes changed lines from context, and labels severity as model-assigned.  
**And** source verification attests to location and snippet only.

**Given** multiple verified candidates with the same normalized issue and overlapping HEAD location  
**When** the results are deduplicated  
**Then** the merged finding keeps each invocation, role and candidate index, uses the AD-11 display and severity rules, and receives a stable ID across repeat runs.

### Story 1.6: Run an all-category single-agent review

As the operator,  
I want a runnable single-agent review over the tiny fixture,  
So that I can inspect source-checked results and establish the baseline for comparing the team.

**Traceability:** FEATURE-006 · FR-6–8, FR-13–17 · NFR-2–5, NFR-7 · AD-2–4, AD-6–9, AD-11–12, AD-15, AD-17–18.  
**Dependencies:** Stories 1.2–1.5.

**Acceptance Criteria:**

**Given** the tiny fixture and a local worker  
**When** the operator submits `SINGLE_AGENT` via the local Temporal entry point  
**Then** the shared Workflow uses exactly one `reviewSpecialist` invocation with role `SINGLE` and runs zero Planner Activities.  
**And** the role covers all review categories, uses the pinned model and prompt version and the common read policy, and returns typed candidates, questions, summary, coverage and usage.

**Given** malformed output, a retry, or a configured budget limit  
**When** the invocation runs with the scripted model or HTTP stub  
**Then** Java enforces elapsed, token, tool and estimated-cost allowances before model/tool work across attempts, applies at most one in-budget repair, and records stopped or rejected work.  
**And** the same budget and finalization contracts can serve team mode.

**Given** a completed single-agent invocation with mixed evidence dispositions  
**When** `validateAndReport` returns the local result  
**Then** the operator can inspect coverage, findings and candidate dispositions from the shared validator.  
**And** the test captures a replayable single-agent history and verifies its replay without provider calls.

## Epic 2: Run and recover an auditable team review

Submit, track and cancel a planned team review through REST, inspect its decisions, coverage and costs in published reports, and demonstrate that it recovers after a worker restart.

### Story 2.1: Validate Planner proposals and enforce run budgets

As the operator,  
I want the Planner to propose focused work under enforceable limits,  
So that the Workflow can prevent unauthorized actions and stop spend predictably.

**Traceability:** FEATURE-007 · FR-9, FR-11, FR-12, FR-17, FR-25 · NFR-5 · AD-5–8, AD-12.  
**Dependencies:** Stories 1.2, 1.3 and 1.6.

**Acceptance Criteria:**

**Given** scripted Planner responses  
**When** `planNext` returns a proposal  
**Then** the Workflow accepts only COMPLETE or a well-formed delegation to Correctness, Security or Performance with task, topic and rationale.  
**And** it records decisions and dispositions in order, refuses direct tools or budget changes, applies AD-5 topic normalization, and stops on three consecutive invalid decisions after any allowed repair.

**Given** a scheduled invocation and up to three attempts  
**When** the Workflow reserves allocations and an Activity attempts model or tool work  
**Then** reservations and the 50/30/20 attempt shares stay within aggregate token, tool and microdollar limits, with integer remainders allocated to the first attempt.  
**And** the Activity checks projected request and repair usage before sending and tool allowance before dispatch. Unknown attempts are charged conservatively and only provably unused shares are released.  
**And** configuration tests verify the documented default sequence fits the USD 0.50 ceiling and reject unusable allocations.

**Given** an exhausted topic, delegation, token, tool, cost or elapsed limit  
**When** more work is proposed or the deterministic deadline fires  
**Then** no further work beyond that limit is scheduled, outstanding work is cancelled on elapsed expiry, and the stop records its specific `BUDGET_*` code.  
**And** an eligible Planner may return COMPLETE after the last allowed delegation.

### Story 2.2: Review security with a scoped specialist

As the operator,  
I want a security-focused source review,  
So that I can inspect security findings and the evidence that supports them.

**Traceability:** FEATURE-008 · FR-7, FR-8, FR-10 · NFR-1, NFR-4, NFR-7 · AD-3, AD-9, AD-18.  
**Dependencies:** Story 1.6.

**Acceptance Criteria:**

**Given** a Security task and an allowlisted synthetic snapshot  
**When** the shared specialist Activity dispatches a scripted invocation  
**Then** it loads a versioned Security prompt and returns typed candidate findings, open questions, a work summary and usage linked to the invocation.  
**And** the registered tools are bounded read-only snapshot tools and source instructions cannot change their policy.  
**And** findings remain untrusted candidates until the common evidence validator classifies them.

### Story 2.3: Review performance with a scoped specialist

As the operator,  
I want a performance-focused source review,  
So that I can inspect potential efficiency problems with source references and context.

**Traceability:** FEATURE-009 · FR-7, FR-10 · NFR-4, NFR-7 · AD-3, AD-9, AD-18.  
**Dependencies:** Story 1.6.

**Acceptance Criteria:**

**Given** a Performance task and a synthetic snapshot  
**When** the shared specialist Activity dispatches a scripted invocation  
**Then** it loads a versioned Performance prompt, uses the same bounded read-only tools, and returns candidates, unresolved questions, a summary and attributable usage.  
**And** tests distinguish the role selection and prompt version from SINGLE invocation data and verify that unsupported claims still follow shared evidence rules.

### Story 2.4: Execute planned specialist work and account for coverage

As the operator,  
I want the team to execute focused tasks and account for what it inspected,  
So that I can distinguish a completed review from interrupted or limited work.

**Traceability:** FEATURE-010 · FR-3–5, FR-9–12, FR-17, FR-20–21, FR-25–26 · NFR-2, NFR-5 · AD-2–8, AD-12, AD-15–18.  
**Dependencies:** Stories 2.1–2.3.

**Acceptance Criteria:**

**Given** a multi-agent request with scripted Planner decisions  
**When** the Workflow dispatches Correctness, Security and Performance, then receives COMPLETE  
**Then** each role runs as a separate Activity with its own pinned prompt version, and a Correctness invocation returns the same typed fields as the other specialists. Later invocations receive an ordered sanitized ledger capped at 4,000 Unicode code points.  
**And** the Activity enforces 20 candidates per invocation and 15 lines and 1,024 UTF-8 bytes per quote, records rejected overflow and keeps policy-checked quotes in history. Worst-case serialized report input fits 512 KiB, and CI replays the captured team history without provider calls.

**Given** truncated, denied, overlapping or descriptor-only tool responses  
**When** the Workflow computes coverage  
**Then** it unions only delivered units by change identity, side and line or descriptor. A file counts as fully covered only when every changed unit was delivered.  
**And** progress records files read and fully covered, changed-line totals and attempted/completed roles. A recoverable stop yields `PARTIAL` with observed coverage or `FAILED` with none and includes structured reasons.

**Given** a retryable provider failure or a permanent refusal  
**When** the work Activity encounters it  
**Then** Temporal alone applies the bounded retry policy, with at most three attempts, 2–30 s backoff and timeouts clamped to remaining elapsed budget.  
**And** permanent errors stop immediately and exhausted provider errors record `PROVIDER_ERROR` and distinguish measured from unknown usage.

**Given** blocking provider work or an active tool loop  
**When** a Workflow Update accepts cancellation before finalization freezes the disposition  
**Then** the worker uses the AD-16 heartbeat interval and timeout, caps provider requests at 60 seconds or the remaining attempt time, stops dispatching further work on notification and aborts in-flight work when supported. The Workflow waits at most 30 seconds for acknowledgment before detached finalization with `CANCELLED` and accounts for unresolved usage.  
**And** a cancellation after the freeze leaves the recorded disposition intact.

### Story 2.5: Publish deterministic review artifacts

As the operator,  
I want auditable JSON and Markdown reports with a verifiable manifest,  
So that I can examine what happened and recover the same report after a write retry.

**Traceability:** FEATURE-011 (publication) · FR-5, FR-15, FR-18–19, FR-25 · NFR-3, NFR-4, NFR-7 · AD-11–13, AD-15, AD-18.  
**Dependencies:** Stories 1.5, 1.6 and 2.4.

**Acceptance Criteria:**

**Given** frozen report input for a completed or partial run  
**When** the shared `validateAndReport` Activity finalizes it  
**Then** it publishes schema-versioned JSON, Markdown, and a manifest with pinned request, workflow/run, fixture, model, prompt, budget and price metadata and artifact digests.  
**And** reports show coverage, reasons, limitations, model-assigned severity, findings, rejected and unverified candidates with explanations, and ordered accepted and rejected Planner decisions.  
**And** failed and cancelled runs receive minimal reports with reasons, coverage and usage, and clean-result wording appears only when the AD-15 conditions hold.

**Given** known tokens, estimates, and unresolved or retried attempts  
**When** the reports and manifest are published  
**Then** per-invocation and aggregate usage distinguishes provider observations, estimated cost, unknown attempts and possible repeat charges.  
**And** structured logs correlate review, Workflow, run, invocation and attempt without source, prompts or secrets.

**Given** overlapping attempts, a lost write acknowledgment, or conflicting report input  
**When** publication stages immutable output and commits the prepared manifest via one supported same-filesystem hard link  
**Then** identical frozen input retrieves the same digest-verified publication and a conflicting input fails permanently.  
**And** a partial write has no readable manifest, transient failures use the bounded report retry policy, and exhaustion preserves the disposition while exposing report unavailability.

### Story 2.6: Submit, poll and cancel reviews through REST

As the operator,  
I want a stable review ID and a public lifecycle API for both modes,  
So that I can submit once, monitor progress, cancel safely, and retrieve reliable report links.

**Traceability:** FEATURE-011 (REST) · FR-1–5, FR-13, FR-18 · NFR-6 · AD-2–4, AD-13–16.  
**Dependencies:** Stories 1.4, 2.4 and 2.5.

**Acceptance Criteria:**

**Given** a valid preflight request in either mode  
**When** the operator submits it with an eligible caller ID or a generated ID  
**Then** the API returns HTTP 202 and Location without awaiting review completion, and the Workflow memo stores the versioned canonical request hash.  
**And** invalid requests produce stable problem responses before model work.

**Given** concurrent or repeated submissions using the same review ID  
**When** the API checks a valid manifest or retained Temporal execution and arbitrates starts through Temporal  
**Then** matching payloads return 200 for the existing review and differing payloads return 409.  
**And** only confirmed absence permits a new start. Transient lookup errors surface as unavailable. After history expiry a valid manifest preserves identity, and unpublished IDs follow AD-14 retention semantics.

**Given** running, cancelled, normally closed, abnormally closed and expired-history reviews  
**When** the operator polls status, requests cancellation or fetches the report  
**Then** the API exposes available progress, budget use, coverage and structured reasons, forwards cancellation through the Workflow Update and exposes links only from a verified matching publication.  
**And** an abnormal server close without a valid matching manifest returns `FAILED` with `INTERNAL_ERROR` and unknown coverage. A valid late publication for the same execution can restore the frozen outcome.  
**And** report fetch uses AD-13 responses for running (409), known unavailable (410), and unknown (404), including reporting timeout and late publication scenarios.

### Story 2.7: Demonstrate durable recovery on persistent Temporal

As the operator,  
I want reproducible crash and failure tests for a real worker process,  
So that I can see which completed work survives and which interrupted work can repeat.

**Traceability:** FEATURE-012 · FR-4, FR-11, FR-14, FR-17–21, FR-26 · NFR-2 · SM-2, SM-4, SM-5 · AD-7–8, AD-13, AD-16–17.  
**Dependencies:** Stories 1.3, 1.6, 2.4–2.6.

**Acceptance Criteria:**

**Given** persistent local Temporal and an independent HTTP stub that counts provider calls  
**When** the worker is killed after a Planner decision is recorded or during a specialist Activity after an earlier specialist succeeds  
**Then** a restarted worker resumes from the same history. Completed Planner and specialist steps make zero extra provider requests.  
**And** any interrupted attempt that repeats is visible in usage and retry accounting.

**Given** recorded single and team histories, and a failure matrix for duplicate publication, rate limiting, malformed output, fake evidence, budget exhaustion and cancellation  
**When** the verification suite replays histories and applies each fault  
**Then** replay succeeds, identical reports remain intact, retries and repairs stay bounded, invalid evidence stays out of findings, and budget and cancellation dispositions remain accurate.  
**And** later Workflow command changes use `Workflow.getVersion` and retain replay compatibility with these histories.

## Epic 3: Measure what the specialist team adds

Run both review modes on the same seeded change under comparable budgets and publish a reproducible comparison of quality, latency and cost.

### Story 3.1: Generate a seeded fixture with isolated ground truth

As the operator,  
I want a reproducible synthetic change and an independently maintained goldset,  
So that both review modes can be judged against the same known defects.

**Traceability:** FEATURE-013 · FR-8, FR-22 · NFR-1, NFR-3, NFR-4 · AD-9–10, AD-19.  
**Dependencies:** Story 1.4.

**Acceptance Criteria:**

**Given** the committed Java/Spring fixture sources and generation script  
**When** two clean directories generate the fixture  
**Then** their reachable BASE and HEAD tags resolve to identical SHAs and the pinned diff fits preflight limits.  
**And** the head builds and includes nine documented synthetic defects and one non-defect control. Include at least one cross-file evidence case and one near-miss control so scoring exercises plausible claims as well as location matching.

**Given** the resulting goldset and pinned diff  
**When** the evaluation scorer loads ground truth  
**Then** it can identify each case by stable ID, root cause, location and failure scenario.  
**And** repository and prompt checks confirm the goldset lives outside every reviewable repository, goldset paths are denied by tool policy, and provider inputs contain no ground-truth records.

### Story 3.2: Run comparable trials and publish the result

As the operator,  
I want repeated single-agent and multi-agent trials scored under the same rules,  
So that I can judge whether the team's quality gains justify its cost and latency.

**Traceability:** FEATURE-014 · FR-13, FR-18–19, FR-22–25 · NFR-3, NFR-5, NFR-7 · SM-1 · AD-4, AD-15, AD-18–20.  
**Dependencies:** Stories 2.5–2.7 and 3.1.

**Acceptance Criteria:**

**Given** the seeded fixture and both review modes available through REST  
**When** the runner submits three to five paired trials with independent review IDs  
**Then** it pins fixture SHAs, provider and model settings, role prompt versions and the common total estimated-cost ceiling in an experiment manifest.  
**And** it retains trial artifacts and dispositions, including incomplete runs, and records effective first-attempt allowances for both modes.

**Given** published findings and the isolated goldset  
**When** the operator adjudicates root cause, location and plausible failure scenario under the same rules for both modes, with mode hidden where possible  
**Then** recall counts distinct matches over nine seeded defects and precision uses semantically valid findings over findings shown.  
**And** the results disclose false positives per run, coverage, latency, estimated costs, partial and failed counts, and Planner decision quality based on accepted, rejected, repeated-topic and terminal decisions. Undefined rates and unavailable data are reported explicitly.

**Given** the recorded trial data, including a result where the team underperforms or the comparison is inconclusive  
**When** the operator prepares the comparison  
**Then** the published summary links the retained trial evidence, reports averages and variability, and shows the cost/quality frontier and limitations without treating a team win as an exit criterion.
