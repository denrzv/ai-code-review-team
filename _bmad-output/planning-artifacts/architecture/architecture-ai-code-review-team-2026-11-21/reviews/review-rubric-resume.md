# Rubric review of the resumed architecture spine

## Verdict

Ready for handoff after lifecycle and evidence acceptance rules are made unambiguous. The revised spine gives the implementers clear ownership of orchestration, budgets, evidence, coverage, publication and evaluation. The local operating envelope, test seams and deferred work are visible.

## High

- **AD-13/14 can expose two outcomes for one review.** A filesystem publication can win after the report Activity times out. The retained Workflow can then expose `FAILED` with no report links while a later manifest-only lookup exposes the intended `COMPLETED` disposition. The rule for manifest-first duplicate lookup can reproduce the discrepancy even within retention. Define a single durable publication commit point and its authority when the last Activity acknowledgement is lost. Test a writer that resumes after the Activity timeout and a successful write whose acknowledgement is lost. A no-replace operation prevents multiple publishers from winning, but cannot decide whether the one winner is still eligible to publish.
- **AD-11 can produce a finding with no evidence.** “Every required reference verifies” succeeds vacuously for an empty reference list. Accepting a blank normalized quote can likewise yield an empty match at a valid location. Require at least one nonblank source reference and require every supplied reference to verify before the candidate becomes a finding. Define candidate-level disposition when reference statuses differ.

## Medium

- **AD-15/16 need to define reported role counts and simultaneous stop events.** Specify whether historical failed attempts constitute an attempted role and which Activity stop reason counts as completion. Give a deterministic precedence to an accepted cancellation, a run timer and a work result received together. Assert the precedence with replay or a Workflow unit test.
- **The dependency table contradicts evaluation's prescribed inputs.** The evaluation row allows report files and `domain` only, while AD-10 and AD-20 require goldset data and the public REST API. Name the dependencies separately for the REST trial driver and the artifact scorer. Both should consume the public schema without depending on Workflow internals.
- **AD-8 mandates a cross-mode experiment whose nominal tool limit differs from its happy path.** An equal three-way split leaves SINGLE with 10 of its nominal 30 tool calls on its successful first attempt and each specialist with four of its nominal 12. The conservative allocation is a sound bound on total spend. Define or disclose the attempt policy and report allocations in the experiment metadata so readers can interpret quality differences and reproduce trials.
