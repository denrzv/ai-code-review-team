# Contracts — Domain, Activities, Tools and API

**Status:** design-level Java sketches, **not compile-ready code**. Generate actual implementation against resolved versions of Temporal and LangChain4j; do not fake method names or merge framework concerns into domain types.

## Design invariants

- No LLM, filesystem, network or JGit I/O inside Temporal Workflow implementations.
- All agent tool objects are **bound to one immutable** `ReviewSnapshot`.
- Structured planner decisions validated against an enum allowlist and enforced budgets.
- `SOURCE_VERIFIED` only attests that source *location and snippet* match pinned HEAD, not that the reported problem is real.
- Temporal Event History holds small serializable DTOs, not complete source files or tool transcripts.
- External DeepSeek API may be called more than once for an unfinished Activity under retry.

## Domain records (candidate)

```java
record ReviewSnapshot(
    String repositoryId,
    String baseCommitSha, // complete canonical SHA
    String headCommitSha  // complete canonical SHA
) {}

enum ReviewMode { SINGLE_AGENT, MULTI_AGENT }
enum ReviewStatus { SUBMITTED, RUNNING, COMPLETED, PARTIAL, FAILED, CANCELLED }
enum ReviewerRole { CORRECTNESS, SECURITY, PERFORMANCE, SINGLE }
enum Category { CORRECTNESS, SECURITY, PERFORMANCE, CONCURRENCY, DATA_INTEGRITY, RELIABILITY }
enum Severity { CRITICAL, HIGH, MEDIUM, LOW, INFO }
enum EvidenceKind { CHANGED_LINE, CONTEXT_LINE }
enum EvidenceStatus { SOURCE_VERIFIED, NEEDS_REVIEW, INVALID_EVIDENCE }

record SourceReference(String filePath, int startLine, int endLine, String quotedCode) {}
record FindingCandidate(
    ReviewerRole reviewer,
    Category category,
    Severity severity,
    String title,
    String explanation,
    String failureScenario,
    String recommendation,
    List<SourceReference> references
) {}
record CodeEvidence(
    String filePath,
    String headCommitSha,
    int startLine,
    int endLine,
    String codeSnippet,
    EvidenceKind kind
) {}
record ReviewFinding(
    String id,
    ReviewerRole reviewer,
    Category category,
    Severity severity,
    EvidenceStatus evidenceStatus,
    String title,
    String explanation,
    String failureScenario,
    String recommendation,
    List<CodeEvidence> evidence,
    List<String> validationNotes
) {}
```

Full commit SHA is preferred for all snapshot and evidence records; validate HEAD/base existence and ancestry/compare policy in the Git adapter. Line numbers refer to **HEAD** for changed file citations; deletions in base must explicitly refer to BASE or be handled as an unsupported evidence type in v1.

## Orchestration records

```java
enum NextAction { DELEGATE, COMPLETE }
record PlanDecision(
    NextAction action,
    ReviewerRole agent,   // null only for COMPLETE
    String task,
    String rationale,
    String topicKey       // normalized reason for repeats/dedup
) {}
record AgentTask(
    String reviewId,
    String agentInvocationId,
    ReviewSnapshot snapshot,
    ReviewerRole role,
    String instruction,
    String previousEvidenceSummary,
    int maxToolCalls
) {}
record AgentResult(
    String invocationId,
    ReviewerRole role,
    List<FindingCandidate> candidates,
    List<String> unresolvedQuestions,
    String summarizedWork,
    AgentUsage usage
) {}
record AgentUsage(
    String provider, String model,
    long inputTokens, long outputTokens,
    int llmRequests, int toolCalls
) {}
record ReviewCommand(
    String reviewId, ReviewSnapshot snapshot, ReviewMode mode,
    ReviewBudget budget
) {}
record ReviewBudget(
    int maxDelegations, int maxInvocationsPerTopic,
    int maxToolCallsPerInvocation,
    long maxEstimatedInputTokens, long maxEstimatedOutputTokens,
    long maximumElapsedSeconds
) {}
record ReportReference(String jsonPath, String markdownPath, String sha256) {}
record ReviewRunResult(
    String reviewId, ReviewStatus status,
    ReportReference report,
    List<String> completionReasons
) {}
```

Do not allow a Planner LLM to increase ReviewBudget or to submit arbitrary agent class names. Budget is validated by application/Workflow. Record actual usage if the provider exposes it; fall back to explicitly labeled estimates.

## Temporal Java Interfaces (concept)

```java
@WorkflowInterface
interface CodeReviewWorkflow {
    @WorkflowMethod
    ReviewRunResult review(ReviewCommand command);

    @QueryMethod
    ReviewProgress progress();
}

@ActivityInterface
interface PlanActivities {
    PlanDecision planNext(PlannerInput input);
}

@ActivityInterface
interface SpecialistActivities {
    AgentResult reviewCorrectness(AgentTask task);
    AgentResult reviewSecurity(AgentTask task);
    AgentResult reviewPerformance(AgentTask task);
    AgentResult reviewSingle(AgentTask task);
}

@ActivityInterface
interface ReportActivities {
    ReportReference validateAndAssemble(ReportInput input);
}
```

Note: Actual imports, serialization and `@ActivityMethod` names must be validated against the selected Temporal SDK. Workflow state is deterministic; Activities perform all effects. Prefer activities with bounded `StartToCloseTimeout` / `ScheduleToCloseTimeout` and explicit retry/non-retry classes. Use heartbeat for long-running cancellable activities if needed.

## LangChain4j agent boundary

```java
interface SpecialistAgent {
    AgentResult review(AgentTask task);
}
interface PlannerPort {
    PlanDecision plan(PlannerInput input);
}
```

Adapters implement these ports through LangChain4j and the configured `ChatModel`, with role-specific, versioned prompts. Do **not** use `AgenticServices.supervisorBuilder()` to control global execution under Temporal. A specialist may use LangChain4j internal tool-calling loops inside its own Activity; these loops are not checkpointed individually in v1.

## Git repository read port

```java
interface RepositoryReadPort {
    List<ChangedFile> listChangedFiles(ReviewSnapshot snapshot);
    DiffExcerpt getDiff(ReviewSnapshot snapshot, String relativePath);
    SourceExcerpt readFile(ReviewSnapshot snapshot, String relativePath, int first, int last);
    List<CodeSearchHit> searchCode(ReviewSnapshot snapshot, String pattern, int limit);
    List<String> findRelatedTests(ReviewSnapshot snapshot, String relativePath, int limit);
    BuildMetadata readBuildMetadata(ReviewSnapshot snapshot);
}
```

Agent-facing tool signatures should be small and descriptive (annotate with LangChain4j `@Tool` / `@P` after version verification):

```text
listChangedFiles()
getDiff(path)
readFile(path, startLine, endLine)
searchCode(query)
listRelatedTests(path)
getBuildMetadata()
```

Tool contract: bounded text; line numbers; stable HEAD/base source; allowlist and canonical path checks; restrictive symlink/submodule policy; redaction of secrets; max 200 lines per `readFile`, bounded diffs/results, and no write/execute/network calls. Do not permit source-controlled content to override tool access policy.

## REST API (minimal)

`POST /api/reviews` asynchronously starts `review-{reviewId}` and returns HTTP 202 and location. Stable, caller-provided or server-generated review ID must have idempotent conflict semantics.

```json
{
  "repositoryId": "sample-orders",
  "baseCommit": "<full-base-sha>",
  "headCommit": "<full-head-sha>",
  "mode": "MULTI_AGENT"
}
```

`GET /api/reviews/{reviewId}` returns state/progress and report links. `GET /api/reviews/{reviewId}/report` returns stored report when complete/partial. `POST /api/reviews/{reviewId}/cancel` requests cancellation. Avoid blocking HTTP while the review is running.

## Final report JSON sample

```json
{
  "schemaVersion": "1",
  "reviewId": "rev-demo-001",
  "status": "PARTIAL",
  "snapshot": {"repositoryId":"sample-orders","baseCommitSha":"<full-base-sha>","headCommitSha":"<full-head-sha>"},
  "findings": [{
    "id": "F-001",
    "reviewer": "CORRECTNESS",
    "category": "DATA_INTEGRITY",
    "severity": "HIGH",
    "evidenceStatus": "SOURCE_VERIFIED",
    "title": "Potential duplicate processing after Kafka redelivery",
    "explanation": "The consumer appears to call an insert path without visible deduplication.",
    "failureScenario": "A redelivered message could create a duplicate row if no database uniqueness rule exists.",
    "recommendation": "Verify schema uniqueness and processing transaction; introduce idempotency if absent.",
    "evidence": [{
      "filePath": "src/main/java/demo/OrderConsumer.java",
      "headCommitSha": "<full-head-sha>",
      "startLine": 42,
      "endLine": 46,
      "codeSnippet": "orderService.createOrder(event);",
      "kind": "CHANGED_LINE"
    }],
    "validationNotes": ["Source snippet matched; business defect not yet proven"]
  }],
  "unverifiedCandidates": [],
  "coverage": {"filesReviewed":3, "filesChanged":4, "completedRoles":["CORRECTNESS"],"incompleteRoles":["SECURITY","PERFORMANCE"]},
  "limitations": ["Stopped due to execution budget"],
  "usage": {"inputTokens":15000,"outputTokens":2500,"estimatedCostUsd":null}
}
```

This JSON is **illustrative**, not a claim about real code or a real model response. Distinguish `unverifiedCandidates` from rejected candidates, and do not equate model-assigned severity with proven business impact.

## Serialization and compatibility

Use Jackson records/DTOs with explicit schema versions, deterministic serialization assumptions and immutable `reviewId`/snapshot. Avoid passing anonymous polymorphic framework objects as Activity payload. Treat any breaking Workflow change as a replay/versioning concern; add a replay test.

## Pricing and retries

Per-Activity retries can duplicate paid DeepSeek calls if completion acknowledgment is lost. `AgentUsage` may undercount real vendor charges across retries; telemetry should also record attempt IDs, if supported. Claim **at-least-once**, never exactly-once vendor billing.

## References

- https://docs.temporal.io/develop/java
- https://docs.langchain4j.dev/tutorials/tools/
- https://api-docs.deepseek.com/api/create-chat-completion/
