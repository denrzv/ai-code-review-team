# Assumptions, constraints and risks

## Explicit assumptions

| ID | Assumption / constraint | Consequence / validation |
|---|---|---|
| A-01 | This is an educational PoC, not a banking production system | Optimize for transparent control/evaluation, not scale |
| A-02 | Review subjects are synthetic or explicitly approved for DeepSeek transmission | No real bank/private proprietary code until governance approval |
| A-03 | Java 25 and Spring Boot 4.1.x are acceptable | Validate transitive dependencies and build with the exact JDK |
| A-04 | DeepSeek `deepseek-flash` supports required non-thinking Tool Calling via chosen adapter | Mandatory smoke test of multiple sequential Tool Calls |
| A-05 | Temporal Service is available locally with persistent state for restart demos | Worker restart without deleting Temporal persistence |
| A-06 | Each agent invocation is bounded and short enough to fit one Activity | Internal calls are not individually durable |
| A-07 | Git comparison is commit-to-commit on a single allowlisted repo | Ignore working tree and reject uncommitted-only changes |
| A-08 | Tools are read-only, no project code execution and no arbitrary shell | Dynamic verification is initially out of scope |
| A-09 | Source citations are HEAD-based for present lines | Deleted-only source requires later evidence contract extension |
| A-10 | Human reviewer remains responsible for acting on findings | No automated code modifications or PR comments |
| A-11 | Reports stored on local disk are sufficient for one Worker during PoC | Introduce shared/object store if scaling/redeploying |
| A-12 | No independent app DB is needed for execution state | Temporal is workflow persistence; artifacts are separate |
| A-13 | LangChain4j Agentic APIs may be experimental | Prefer explicit planner interface and stable core Tool APIs |
| A-14 | Input size is bounded (fixture-scale) | Defer large-repo indexing and prompt compaction |
| A-15 | Deterministic source validation is not proof of logical defect | Human/goldset evaluation required |
| A-16 | Provider pricing/model behavior can change | Recheck pricing at implementation and log model version/usage |

## Key risks

| Risk | Impact | Control |
|---|---|---|
| DeepSeek adapter incompatibility with provider-specific thinking/tool protocol | Calls fail or tools never execute | Spike first, raw HTTP debug with secrets redacted, pin compatible versions |
| Temporal replay nondeterminism | Run repeatedly fails on recovery | Activities for all effects; Workflow replay tests |
| Double billing after Activity restart | Increased spend | Explicit bounded retry/backoff; log attempts and cost; no exactly-once promise |
| Agent repeats delegation indefinitely | High latency/spend | Hard max delegations, per-topic repeats, elapsed budget |
| Hallucinated review finding | False positive | Source validation, false-positive evaluation, confidence as uncertainty not proof |
| Prompt injection in code comments | Tool misuse or data exfiltration | Strict capability tool allowlist; never shell/network/write tool; minimal API credentials |
| Large diffs overflow context and Temporal payloads | Failure/expense | Limits; content digests and references; `PARTIAL` result |
| Process restart loses local reports if disk is ephemeral | Missing artifacts despite durable workflow | Persistent local volume or object store; atomic write and report reconciliation |
| Git garbage collection removes pinned objects | Source can no longer be verified | Keep stable repo fixture/object reachability through execution |
| Leaked tokens or code in traces | Compliance/security breach | Redaction, minimal logging, secret scanners, privacy policy |
| Integration framework versions drift | Build breaks | Pin via BOM and lock documented compatibility matrix |
| Misleading multi-agent superiority | Wasted complexity | Single-agent baseline with matched budgets and repeated trials |

## Definition of done for reliability

- Kill JVM during a specialist Activity; Worker restart resumes without re-running earlier successful Activities.
- Confirm interrupted Activity may retry and that this behavior is recorded.
- Stop on max delegations or elapsed budget with `PARTIAL`, never misleading empty clean report.
- Corrupt a file/line reference in mocked LLM output; FindingValidator rejects it.
- Deny path traversal and symlink escape by tool tests.
- Re-run same frozen fixture and compare reproducible summary metrics.

## Questions to resolve during implementation

1. Actual compatible releases: Temporal Java SDK, LangChain4j/Agentic artifacts, JGit, and Maven Surefire for Java 25.
2. Does DeepSeek Flash reliably support structured planner JSON + Java tool schema over `langchain4j-open-ai`?
3. What retry exceptions represent permanent bad requests vs rate limiting/transient server errors?
4. Will shell-free JGit search across tree blobs be adequate for the seeded fixture?
5. How to reconcile report files if a report-write Activity succeeds but acknowledgment is lost?
6. Should full model transcript be stored as a separate opt-in, redacted debug artifact?
7. Does seeded error set include hard-to-verify cases where tests or schema constraints are needed to avoid incorrect claims?

## Out of scope

Temporal Cloud production topology, access control for untrusted remote users, enterprise audit retention, automated pull request writing, CI-executed arbitrary code, confidential repository access, autonomous code repair, external web search, LangGraph and vector RAG.
