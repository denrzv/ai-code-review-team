# Addendum: AI Code Review Team PRD

This addendum holds technical depth and source detail kept out of `prd.md`. The authoritative design is in `docs/`. This file points there and records what the PRD pass found.

## A. Technology baseline (from `docs/`, for architecture)

- Java 25 LTS, Spring Boot 4.1.x, Maven. Exact versions to be validated against Maven Central.
- Temporal Java SDK, with one Workflow per `reviewId`. The Planner and each specialist invocation are Activities. No I/O, clock or randomness inside Workflow code.
- LangChain4j for Planner and specialist inference and for tool loops inside Activities. No nested LangChain4j supervisor as the scheduler.
- DeepSeek (`deepseek-flash`, non-thinking) through an OpenAI-compatible adapter. The model name and its tool-calling behavior are unverified (ADR-002 spike).
- JGit read port. Artifacts are stored as files outside Temporal history. No application DB.

## B. Document inconsistencies found during extraction (for architecture and epics)

1. `ReviewBudget` has no cost field, although rules require cost limits. The PRD adds one (PRD FR-11).
2. The elapsed-time budget clashes with "no wall clock in the Workflow". Workflow time APIs or timers are needed (PRD OQ-6).
3. Tool names differ between the Git port and the agent tools (`findRelatedTests`/`listRelatedTests`, `readBuildMetadata`/`getBuildMetadata`, `searchCode` signatures).
4. No FEATURE owns `SINGLE_AGENT` mode or `reviewSingle`. FEATURE-006 is Correctness-only (PRD FR-13).
5. The loop pseudocode has no `SINGLE` case, yet the Planner enum lets the Planner pick `SINGLE`.
6. The reasons field is named three ways: `reasons[]`, `completionReasons`, `limitations`.
7. The report schema lacks rejected candidates. `NEEDS_REVIEW` semantics are undefined (PRD FR-15).
8. The architecture says "VERIFIED" where the enum is `SOURCE_VERIFIED`.
9. `ReportReference` lacks a manifest path, and its single sha256 is ambiguous.
10. The dependency table and diagram disagree for FEATURE-011, 012 and 014.
11. No synthetic repo exists before FEATURE-013, but FEATURE-004..006 need one (PRD OQ-7).
12. The goldset sits next to the fixture, and no exclusion mechanism is specified (PRD FR-8).
13. It's unclear where `SUBMITTED` lives before the Workflow starts. Several referenced types are undefined.
14. Most limits have no numeric defaults. Only the 200-line limit for `readFile` is concrete.
15. Idempotency conflict semantics are undefined (PRD FR-2).
16. No approval path exists for "explicitly approved" non-synthetic code. v1 treats this as a non-goal and uses synthetic code only.
17. Precision and recall use different matching rules. The PRD resolves this (PRD FR-23, matching rule).

## C. Landscape notes (research digest: `research-landscape.md`)

- Noise is the main failure mode of AI reviewers. The low-noise pattern that has worked is parallel finders, then a verification pass, then severity ranking. This matches the project's validator-gate design.
- No independent, reproducible benchmark exists, and vendor numbers can't be compared. A published goldset with root-cause matching is itself a differentiator for a learning project.
- Metrics worth adding beyond precision and recall: false positives per PR, file/line accuracy, duplicate rate, cost and latency per review.
- Specialist splits raise recall, but each added agent helps less and adds cost and duplicates. This is the exact hypothesis SM-1 tests.
- Temporal plus LLM pitfalls: disable the LLM client's own retries so they don't stack on Activity retries. Don't retry 4xx or content-policy errors. Heartbeat long Activities. Keep payloads out of history. Double billing on a lost acknowledgment is unavoidable.
