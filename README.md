# AI Code Review Team

A reproducible multi-agent code-review proof of concept with **Java 25**, **Spring Boot 4.1.x**, **LangChain4j**, **DeepSeek Flash**, and **Temporal**.

> Status: **architecture and implementation plan only**. No runtime implementation has been committed yet. All review targets must be synthetic or explicitly authorized for transmission to the configured LLM provider.

## Mission

Review a fixed Git diff using specialized **Correctness**, **Security**, and **Performance** agents. A **LLM Planner** selects which agent to call next; a **Temporal Workflow** is the single source of truth for durable orchestration. Findings are validated by deterministic Java code before publishing JSON and Markdown reports.

**Core design rule:** LLMs suggest decisions and evidence; Temporal controls execution; Java validates claims and enforces security boundaries.

## Documentation

- [Architecture](docs/architecture.md): C4-style logical design, Temporal execution, failure scenarios and contracts.
- [ADRs and trade-offs](docs/adr/README.md): recorded decisions, alternatives, consequences, and open points.
- [Contracts](docs/contracts.md): Java-oriented domain and orchestration contracts, tool permissions, report schema.
- [Implementation roadmap](docs/implementation-plan.md): delivery sequence and GitHub issue mapping.
- [Evaluation](docs/evaluation.md): gold fixtures, safety checks, baseline comparison, durability tests.
- [Assumptions and risks](docs/assumptions-and-risks.md): boundaries, explicit non-goals and validation spikes.
- [Claude Code instructions](CLAUDE.md) and [Codex instructions](AGENTS.md).

## Target stack

| Area | Baseline |
| --- | --- |
| Java | 25 LTS |
| Spring Boot | 4.1.x (validate exact release via Maven) |
| Orchestration | Temporal Java SDK; a Workflow per review run |
| AI | LangChain4j; one LLM Planner + specialized agents |
| Model | DeepSeek `deepseek-flash`, non-thinking first |
| Repository access | JGit, pinned base/head SHAs, read-only allowlisted repos |
| Persistence | Temporal service for execution; versioned JSON/Markdown artifacts for results |
| Testing | JUnit, synthetic seeded Java test repository, controlled worker restart |

**No PostgreSQL, Kafka, Redis, MCP or write-capable Git tools for v1.** A local Temporal development server is sufficient for experimentation; persistent server storage must survive a worker restart for durability tests.

## Planned workflow

```text
POST /api/reviews
    -> snapshot base/head SHAs
    -> Temporal CodeReviewWorkflow
    -> PlanNext Activity (DeepSeek)
    -> [Correctness | Security | Performance] Activity (LangChain4j + read-only Git tools)
    -> repeat within budgets
    -> deterministic FindingValidator and ReportAssembler
    -> ReportReference (JSON + Markdown)
```

The planner may delegate repeatedly, but **does not execute inside Temporal Workflow code**. Calls to DeepSeek, JGit, filesystem and external APIs occur inside Activities or application adapters only.

### Durability guarantee (v1)

Temporal checkpoints **completed Activities**, **not every internal LLM or tool call**. If an agent Activity crashes mid-execution it may run again and incur another bill. No exactly-once claim for external LLM requests. Per-call durability is a possible v2 design.

## First implementation step

Start with GitHub issue **FEATURE-001 : Project Bootstrap**, then continue in dependency order using [the roadmap](docs/implementation-plan.md).

## Official references

- [Temporal Java](https://docs.temporal.io/develop/java)
- [Temporal Workflow determinism](https://docs.temporal.io/workflow-definition)
- [LangChain4j agents](https://docs.langchain4j.dev/tutorials/agents/)
- [DeepSeek API](https://api-docs.deepseek.com/)
- [Spring Boot docs](https://docs.spring.io/spring-boot/reference/)

## Security

Never expose a writable repository path, unrestricted shell tool, credentials, or internal bank code to this PoC. Treat source files, comments, diffs and LLM output as untrusted input. API keys must be injected via environment variables or secrets, never committed.
