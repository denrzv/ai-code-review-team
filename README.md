# AI Code Review Team

A reproducible multi-agent code-review proof of concept with **Java 25**, **Spring Boot 4.1.x**, **LangChain4j**, **DeepSeek Flash**, and **Temporal**.

> Status: **local service bootstrap**. Review execution is planned. All review targets must be synthetic or explicitly authorized for transmission to the configured LLM provider.

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
| Build | Temurin 25 and Maven 3.10.1 via Maven Wrapper |
| Spring Boot | 4.1.3 |
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

## Build and run locally

Install Temurin JDK 25 and point `JAVA_HOME` to it. Run the following commands from the repository root. The wrapper downloads and verifies Maven 3.10.1 on its first run. On Unix, install `unzip` so the wrapper can verify the ZIP checksum.

```sh
./mvnw -v
./mvnw -B clean verify
"$JAVA_HOME/bin/java" -jar target/ai-code-review-team-0.1.0-SNAPSHOT.jar
```

On Windows, run these commands in Command Prompt.

```cmd
mvnw.cmd -v
mvnw.cmd -B clean verify
"%JAVA_HOME%\bin\java.exe" -jar target\ai-code-review-team-0.1.0-SNAPSHOT.jar
```

With the application running, check health from another terminal.

```sh
curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health
```

Expect HTTP 200 with a JSON `status` of `UP`. If port 8080 is in use, pass `--server.port=8081` when starting the JAR and use port 8081 in the health URL. The bootstrap starts with no DeepSeek key or Temporal server. `POST /api/reviews` returns HTTP 404 until review operations are implemented.

The defaults live in [application.yaml](src/main/resources/application.yaml). Override non-secret values with Spring configuration, for example `REVIEW_OUTPUTDIR=custom/reviews` or `--review.output-dir=custom/reviews`. The provider credential source for later integration is `${DEEPSEEK_API_KEY}`.

See the [compatibility record](docs/compatibility.md) for the resolved stack and [roadmap](docs/implementation-plan.md) for subsequent features.

## Official references

- [Temporal Java](https://docs.temporal.io/develop/java)
- [Temporal Workflow determinism](https://docs.temporal.io/workflow-definition)
- [LangChain4j agents](https://docs.langchain4j.dev/tutorials/agents/)
- [DeepSeek API](https://api-docs.deepseek.com/)
- [Spring Boot docs](https://docs.spring.io/spring-boot/reference/)

## Security

Never expose a writable repository path, unrestricted shell tool, credentials, or internal bank code to this PoC. Treat source files, comments, diffs and LLM output as untrusted input. API keys must be injected via environment variables or secrets, never committed.
