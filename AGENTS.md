# Codex Agent Instructions

Read `CLAUDE.md` plus all files under `docs/` relevant to your FEATURE ticket. These are design constraints, not mere suggestions.

## Guardrails

- Do not implement multiple issues without request.
- Favor deterministic Java controls over prompt-only restrictions.
- **Only Temporal controls global orchestration.** LLM Planner and each Specialist execute in Temporal Activities; no LLM/JGit/HTTP/filesystem effects in Workflow code.
- LangChain4j is the model/tool adapter, not an independent persistent Supervisor.
- The multi-agent team is: Planner + Correctness, Security, Performance; single-agent baseline is mandatory.
- Git tooling is read only, pinned to immutable base/head SHAs and allowlisted repository IDs; no arbitrary shell.
- No real confidential source upload or secrets in commits/traces.
- Evidence validation verifies source references, not that semantic findings are correct.
- Bounded retries, tool calls, token usage and wall-clock; interrupted runs can be PARTIAL.
- Add tests and update ADRs when behavior/architecture changes.

## Workflow for code changes

1. Read the exact GitHub issue and dependent merged work.
2. Check release/API compatibility and compile in Java 25.
3. Implement only in the correct domain/application/adapter/orchestration package.
4. Run unit/integration tests relevant to the feature.
5. Report known limitations and how to reproduce tests.

Read [implementation plan](docs/implementation-plan.md) and [contracts](docs/contracts.md) for target interfaces and acceptance criteria.

**Never infer from documentation snippets that code compiles; verify APIs in the resolved dependency version.**
