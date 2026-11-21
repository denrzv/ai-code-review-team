# Epic 1 Context: Review a pinned change with one agent

<!-- Compiled from planning artifacts. Edit freely. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Run an all-category single-agent review of a reproducible synthetic Git change through local Temporal. The operator can inspect checked source citations, candidate dispositions, coverage and usage. The run establishes the baseline for the later team comparison and produces a Workflow history that can be replayed independently of the provider.

## Stories

- Story 1.1: Verify the local Java 25 build
- Story 1.2: Verify the DeepSeek review protocol
- Story 1.3: Run a Workflow on local persistent Temporal
- Story 1.4: Inspect a reproducible pinned Git change
- Story 1.5: Verify candidate evidence against pinned HEAD
- Story 1.6: Run an all-category single-agent review

## Requirements & Constraints

- Run verification on a clean Java 25 environment without a provider key or external Temporal server. Use scripted model responses or a local HTTP stub and the in-process Temporal test environment. Keep live provider checks opt-in and read credentials from the environment.
- Accept only allowlisted synthetic repositories and full, reachable commit IDs. Require BASE to be an ancestor of HEAD and the committed diff to be nonempty and within 20 changed files and 1,500 changed lines. Freeze change identifiers, rename information and side-specific line ranges at preflight. Generate fixtures from committed inputs and fixed Git metadata so independent builds produce identical SHAs.
- Use one content policy for the review tools and evidence validator. Keep the goldset outside all reviewable repositories and deny goldset paths. Refuse traversal, symlinks, out-of-policy submodules, binary data and oversized content. Redact secrets without moving source line positions. Treat source and model output as untrusted data. Tool capabilities are limited to reads of pinned commits.
- Track reproducibility by fixture, SHAs, model, prompt version and budget. Attribute available usage to the invocation and attempt, retain unknown usage explicitly, and distinguish observed tokens from estimated cost. Exclude keys, raw source, transcripts and prompts from logs.
- Apply token, tool-call, estimated-cost and elapsed-time limits in Java before dispatch. Reserve allowances across attempts and account conservatively for unknown usage. The completion disposition reflects recorded coverage. An interrupted budget or provider stop with observed change units produces `PARTIAL`, and one with zero observed change units produces `FAILED`.

## Technical Decisions

- Use one Maven module and one Spring Boot executable. The planned stack is Temurin 25, Maven 3.10.1, Spring Boot 4.1.3, Temporal Java SDK 1.41.1, LangChain4j 1.24.0, JGit 7.8.1.202611041320-r and ArchUnit 1.5.1. Confirm compatibility with the resolved build. Keep the domain JDK-only and application ports dependent on the domain. Put API, Git, model and artifact adapters behind their boundaries and enforce package dependencies during the build.
- Temporal owns run state and orchestration. The single Workflow type uses `review-{reviewId}` and deterministic time and IDs. Its implementation works with domain records, Activity interfaces and the Workflow API. Activities perform provider and repository work and finalize results. Use the plain SDK and bounded records across Temporal, with the SDK Jackson 2 converter and Spring Boot Jackson 3 for HTTP and reports. Local persistence uses the Temporal CLI 1.10.0 database file and a verified 30-day development namespace retention.
- Build the model adapter with LangChain4j and verify DeepSeek `deepseek-flash`, thinking disabled, bounded output tokens and Planner JSON object mode at the wire boundary. Disable provider built-in tools and automatic client and transport retries. Temporal retries only transient provider or transport failures. Treat invalid requests, authentication failures and policy refusals as permanent. Return a typed rejection if malformed output remains after at most one in-budget repair.
- Use the same Workflow, role-scoped tool policy, validation and budget contracts for both review modes. For `SINGLE_AGENT`, run one all-category `reviewSpecialist` invocation with role `SINGLE` and zero Planner Activities. Count only changed units actually delivered under policy and truncation. Track descriptor-only changes separately and preserve unavailable reads as limitations.
- Run evidence validation once in `validateAndReport` through the restricted repository port. Require canonical pinned HEAD citations with one-based inclusive ranges. A contiguous normalized match within the cited range yields `SOURCE_VERIFIED`. Keep eligible partial matches as `NEEDS_REVIEW` and shifted, fabricated or denied references as `INVALID_EVIDENCE`. A finding requires a nonblank quote and verification of every reference. Merge overlaps by normalized issue and location with stable IDs and all contributing invocation provenance. Severity is model-assigned, and the operator judges the claim.

## UX & Interaction Patterns

The operator submits the tiny fixture through the local Temporal entry point, inspects progress and completion, and reads coverage, findings and candidate dispositions in the result. The public REST lifecycle and published reports ship later.

## Cross-Story Dependencies

- The verified build supports the provider, Temporal and pinned Git work, which can proceed independently. The evidence validator consumes the pinned Git port and its content policy.
- The single-agent run combines the verified provider adapter, Temporal worker, pinned Git tools and validator. Its budgets, tools, validation and replay boundary also support team mode and the comparative trials.
