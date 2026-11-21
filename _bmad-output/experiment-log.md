# Experiment Log — BMad Method branch

Purpose: record exactly what input the `experiment/bmad-method` line received, so it can be compared fairly with the non-BMad implementation on `main`.

## Shared baseline input (identical on both branches)

- Commit `55169b94e7193b1f92796258f1c688df171c6c71` (`docs: add AGENTS.md`)
- `README.md`, `CLAUDE.md`, `AGENTS.md`, `docs/architecture.md`, `docs/adr/README.md`, `docs/contracts.md`, `docs/implementation-plan.md`, `docs/evaluation.md`, `docs/assumptions-and-risks.md`
- GitHub issues #1–#4 (FEATURE-001..004) as they existed on 2026-11-21
- Rule: `docs/` is not rewritten into BMad formats; BMad artifacts are derived from it into `_bmad-output/`.

## Environment (not planning input)

- Local JDK: Temurin 25 (Homebrew); Maven 3.10.1 on PATH
- BMad installed: commit `b8299ad5f7bb284a8b524aa89d33b090852577ef` (`chore: add BMad method scaffolding`)

## Contamination record

- 2026-11-21: plain Claude (outside BMad skills) produced a FEATURE-001 readiness analysis: doc inconsistencies, six decisions (Spring Boot 4.1.3, `dev.denrzv.codereview` package, empty `/api/reviews` controller, Spotless/ArchUnit, `docs/compatibility.md`, branch strategy) and a Maven Central version scan (Boot 4.2.0/4.1.3, Temporal 1.41.1, LangChain4j 1.24.0, JGit 7.8.1). A `bmad-build` spec built from those decisions was **discarded before implementation**. The analysis survives in the human's memory and Claude session history only; BMad steps below must re-derive decisions on their own.

## BMad step log

| Date | Skill | Input | Output | Notes (time / cost / human interventions) |
|---|---|---|---|---|
| | | | | |
