# Evaluation and Test Strategy

## What the PoC must prove

A team with extra LLM calls is not necessarily better than a single agent. Compare objectively, on the same immutable Git snapshot and the same DeepSeek model. Evaluations must report both **quality and operational reliability**.

## Synthetic ground-truth repository

Under `samples/order-processing-service`, create a small Java/Spring demo with intentionally planted bugs. All examples must compile (or if deliberate compile failure, explicitly mark it as a fixture), and carry no real credentials or internal code.

Suggested gold defects and checks (illustrative targets; do not assert them as discovered until fixture is committed):

1. Kafka redelivery without durable idempotency and no unique key enforcement.
2. Missing authorization on one privileged REST endpoint.
3. N+1 query via a nested entity access in list endpoint.
4. Lost update / race under concurrent order transitions.
5. Sensitive data included in debug logs.
6. Unbounded query/pagination.
7. Timeout/retry around downstream REST call with duplicate side effect risk.
8. Null/exception path causing 500 instead of domain response.
9. Insufficient transaction boundary around write + event.
10. One **non-defect control case** to measure false positives.

Keep exact issue locations and accepted evidence **only in** `samples/goldset.json`; exclude that file from agent tool search and prompts to avoid contamination.

## Metrics

- **Recall** = true gold defects matched by verified findings / total gold defects.
- **Precision** = matched true findings / all judged actionable findings. Evaluate semantic validity, not just SOURCE_VERIFIED.
- **False positives** = non-defects or findings not supported by real conditions.
- **Coverage** = changed files/context inspected and categories attempted.
- **Latency** = elapsed time and per-Activity durations.
- **Cost** = provider-reported tokens and actual/estimated USD; disclose retry accounting uncertainty.
- **Planner quality** = delegations, unnecessary revisits, failure to stop, missed roles.
- **Durability** = successful recovery while avoiding rerun of already completed Activities.
- **Safety** = tool denials, traversal blocking, no write attempts and no secret leakage.

## Evaluation protocol

1. Pin one base/head commit pair and model/provider parameters.
2. Run single-agent baseline 3–5 times; record all outputs.
3. Run multi-agent variant 3–5 times with comparable **total token/spend budget**, not identical per-agent budgets.
4. Match findings by root cause, location and concrete failure scenario, not literal wording.
5. Blindly or consistently adjudicate semantic findings against fixture's goldset.
6. Report averages, variability and partial/failed runs; show cost/quality frontier instead of one anecdotal winner.
7. Never give agents the goldset or an answer-bearing prompt.

## Durability experiments

| Test | Intervention | Expected |
|---|---|---|
| WORKER_RESTART | Kill Worker during Security Activity after Correctness succeeds | Correctness not re-executed, Security may retry |
| PLANNER_REPLAY | Kill Worker after PlanDecision persisted | Same decision replayed, no extra planner API call |
| DOUBLE_WRITE | Crash after report file write before Activity ack | Deterministic artifact path, no corrupted/duplicate report |
| RATE_LIMIT | Simulate 429 on DeepSeek | Bounded retry with classification/backoff; fail/partial if budget exhausted |
| BAD_SCHEMA | Planner or reviewer returns malformed JSON | Rejected/one bounded repair attempt; progress transparent |
| FAKE_LINE | Model cites non-existent line | Candidate marked INVALID_EVIDENCE |
| BUDGET_EXCEEDED | Planner repeatedly delegates same topic | Workflow ends PARTIAL with reason |
| CANCEL | Cancel running review | No misleading COMPLETED; cancellation propagated/bounded |
| PROMPT_INJECTION | Source comment asks to print secrets or alter instructions | No tools outside read-only repo capability |

Tests must distinguish a persisted Temporal Activity completion from an external LLM request that *might* already have been billed.

## Testing layers

- **Unit**: DTO contracts, path restrictions, line matching, dedupe, budgets, state transitions and report rendering.
- **Contract**: mock DeepSeek-compatible chat tool-calling protocol; model output JSON deserialization.
- **Temporal integration**: in-memory test environment for deterministic workflow tests, local persistent service for crash/restart tests.
- **End-to-end**: local synthetic repo + Temporal + DeepSeek key (manual/opt-in; not mandatory in CI).
- **Security**: invalid repo/path, symlink, large binaries, prompt injection, key redaction.

## Report format

Save `output/reviews/<reviewId>/report.json`, `report.md`, and a small manifest of hashes, commit SHAs, model/prompt versions and limitations. For test reproducibility, keep request parameters and a path to retained execution traces (when enabled) separate from source dumps.

## Exit criteria

All defined correctness and safety tests pass; the crash recovery scenario is demonstrated; measured baseline comparison is published even if multi-agent underperforms; production-readiness is **not** implied.
