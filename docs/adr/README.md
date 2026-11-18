# Architecture Decision Records

**Status legend:** Accepted for PoC / Provisional (verify in spike) / Deferred. Decisions are revisitable after evidence from evaluation.

## ADR-001 — One JVM application, logical modules instead of agent microservices
**Status:** Accepted for PoC

**Context:** Correctness, Security and Performance are logical collaborators, not independently owned deployment units.

**Decision:** Single Maven project and Spring Boot executable; split into API, application, domain, agents, git/llm/report infrastructure and Temporal orchestration packages.

**Alternatives:** Independent per-agent microservices; Maven multi-module from day one.

**Trade-offs:** Simplifies development, debugging, integration testing and operational costs; reduces fault isolation and independent scaling. Temporal Worker/API can later split deployments without changing contracts.

**Revisit when:** agent roles require separate trust domains, team ownership or scaling.

## ADR-002 — DeepSeek Flash via model-provider abstraction
**Status:** Provisional, integration smoke test mandatory

**Context:** Want inexpensive multi-agent experiment and reproducible token/cost accounting.

**Decision:** Default model `deepseek-flash` through LangChain4j OpenAI-compatible integration. Disable thinking mode initially using validated provider options. Configure base URL/key/model externally. Parse typed results and validate independently.

**Alternatives:** GPT family, local Ollama/MLX, DeepSeek V4 Pro, direct DeepSeek HTTP client.

**Trade-offs:** Lower expected cost and OpenAI-compatible API; provider-specific JSON/tool/reasoning behavior may differ from LangChain4j support. API compatibility must be proven, not assumed. LLM nondeterminism requires evaluation across repeated runs.

**Revisit when:** tool-call protocol fails, quality insufficient, local privacy required, or model prices change.

## ADR-003 — Temporal is authoritative owner of orchestration lifecycle
**Status:** Accepted for PoC

**Context:** Re-running all specialists after a Worker crash wastes work and model spend. Duplicating persistence in application DB or `AgenticScopeStore` introduces a second execution state machine.

**Decision:** One `CodeReviewWorkflow` per `reviewId`, deterministic scheduling in Temporal. Use Activities for planner calls, specialist agents, validation and artifact I/O. **Do not use LangChain4j supervisorBuilder as the runtime control plane** in v1; implement an LLM Planner as an Activity that returns a typed decision. Temporal validates and executes decisions.

**Alternatives:** In-memory LangChain4j Supervisor; LangChain4j AgenticScope persistence; custom PostgreSQL saga/state machine; Temporal with nested LangChain4j supervisor inside single Activity.

**Trade-offs:** Strong durability and transparent execution/recovery at Activity boundaries; extra infrastructure, replay-aware design, and retry semantics. A nested supervisor would hide delegations from Temporal and impair recovery/observability.

**Revisit when:** lightweight single-shot review is the only production workload or an equivalent durable runtime proves simpler.

## ADR-004 — Agent invocation is the v1 Activity boundary
**Status:** Accepted for PoC, finer boundaries deferred

**Context:** A specialist may make several DeepSeek and Git-tool calls. Durability at every call would require a persisted message/turn state machine.

**Decision:** `PlanNext` is a Temporal Activity; `ReviewCorrectness`, `ReviewSecurity` and `ReviewPerformance` each run as one Activity per delegation. Completed Activity results are durable; partial internal calls are not. Enforce limits and bounded retries.

**Alternatives:** One Activity wrapping the entire team; one Activity per LLM/Tool Call.

**Trade-offs:** Useful incremental recovery and low implementation effort, but an interrupted specialist can repeat calls and cost money. A fully per-call design is more granular but couples the agent loop and message serialization to Temporal.

**Revisit when:** the cost/latency of retried specialist calls is materially high or long-running tasks require finer progress and Human-in-the-Loop.

## ADR-005 — All repository tools are read-only and snapshot-pinned
**Status:** Accepted

**Context:** An agent can hallucinate tool arguments or be prompt-injected by comments in source code.

**Decision:** JGit, allowlisted repository ID, immutable base/head commit hashes. No `git checkout`, shell, modifying files, network tools, or executing project tests in v1. Tools limit path, size, line range and number of calls.

**Alternatives:** Shell-based Git commands; GitHub API retrieval; sandboxed test/build runners.

**Trade-offs:** Much smaller risk and reproducible evidence; no automated dynamic verification and less context for defects requiring runtime behavior. Add sandboxed execution only as a separate ADR.

## ADR-006 — Structured finding candidates plus deterministic evidence validation
**Status:** Accepted

**Context:** LLMs can invent file names, line numbers and confidently wrong diagnoses.

**Decision:** Agents emit `FindingCandidate` objects; application validates file existence at HEAD, commit SHA, line range and quoted snippet. Valid location evidence is `SOURCE_VERIFIED`, **not semantic proof**. Dedupe and assemble reports deterministically. Track rejected/uncertain candidates.

**Alternatives:** Free-form Markdown generated directly by Supervisor; LLM judging its own findings without checking source.

**Trade-offs:** Higher trust and objective review metrics; adds contracts/validation and does not establish actual runtime correctness. Human assessment or controlled tests are still needed.

## ADR-007 — Single-agent baseline and evaluation before complexity claims
**Status:** Accepted

**Context:** A multi-agent setup can increase latency, cost, false positives and error surface without delivering higher recall.

**Decision:** Use a seeded synthetic Java/Spring fixture with a hidden ground-truth defect list. Execute both single-agent and multi-agent modes with the same model, code snapshot, evidence rules and comparable token budgets; track precision, recall, false positives, cost, latency, coverage and reliability.

**Alternatives:** Demo-only final reports judged by intuition.

**Trade-offs:** More initial test-fixture effort; strong evidence for architectural value. Do not claim multi-agent is better until measured.

## ADR-008 — Local Temporal development infrastructure, not embedded workflow storage
**Status:** Accepted for PoC

**Context:** Temporal is a service with persistence, not a library that merely serializes agent state into the JVM.

**Decision:** Use local Temporal dev server with persistence enabled for restart tests; Java SDK Workflow/Worker in application. Do not add application PostgreSQL for execution state.

**Alternatives:** In-memory orchestration; PostgreSQL custom tables; managed Temporal Cloud.

**Trade-offs:** Additional server and setup; ready-made execution history, retries, inspectability. Dev server is **not production deployment topology**.

## ADR-009 — JSON and Markdown artifacts outside Temporal history
**Status:** Accepted

**Context:** Large diffs, LLM conversations and final reports can inflate history size and breach payload limits.

**Decision:** Store small status/decisions/references in history; save final JSON/Markdown under deterministic per-review path. Make writes idempotent/atomic, include hashes and prompt/model version. Keep only bounded summaries for next planner calls.

**Alternatives:** Full source and full transcripts as Workflow state; bespoke DB event log.

**Trade-offs:** Low history size and reproducible artifact inspection; needs lifecycle/cleanup of file artifacts and future object store if distributed.

## ADR-010 — Agent prompts cannot enforce policy; Java and Temporal do
**Status:** Accepted

**Context:** Prompts and agent autonomy are probabilistic. Safety constraints and execution budgets must be hard constraints.

**Decision:** Validate planner action/role/task scope; enforce max delegations, per-role checks, timeout, cost, path boundaries and output validation outside LLM. API/Worker access only to allowlisted synthetic/approved repos. Mark partial work explicitly.

**Alternatives:** Prompt-only rules.

**Trade-offs:** Less unconstrained autonomy but safer, explainable, testable behavior.

## Open decision log

- Determine exact supported **LangChain4j release and Agentic beta alignment** during dependency resolution; avoid copying unverified pre-release sample API blindly.
- Verify DeepSeek Tool Calling, non-thinking parameter transport, JSON output and error behavior with the chosen LangChain4j adapter before implementing orchestrator.
- Confirm Temporal Java SDK and optional Spring integration compatibility with Spring Boot 4.1.x; using the plain SDK is acceptable.
- Decide whether source snippets should be stored in report or replaced with digests if sensitive projects are ever supported.
- Define how to persist billing estimates when a request succeeded remotely but an Activity retry makes client-side accounting uncertain.

## Further reading

- https://docs.temporal.io/develop/java
- https://docs.temporal.io/workflow-definition
- https://docs.langchain4j.dev/tutorials/agents/
- https://api-docs.deepseek.com/
