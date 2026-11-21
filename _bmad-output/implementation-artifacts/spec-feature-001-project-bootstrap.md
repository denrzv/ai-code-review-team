---
title: 'FEATURE-001: Project Bootstrap'
type: 'feature'
created: '2026-11-21'
status: 'done'
baseline_commit: f1a54643db96173d56734ffd4613bfff8a0708f9
route: 'dispatch'
review_loop_iteration: 0
context: ['{project-root}/CLAUDE.md']
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** GitHub issue #1 and Epic 1 Story 1.1 require a reproducible service build. The repository currently contains planning and architecture only.

**Approach:** Bootstrap one Spring Boot Maven executable with a local health endpoint, an empty review controller, external configuration, automated dependency boundaries and secret-free CI.

## Boundaries & Constraints

**Always:** Temurin Java 25, Maven Wrapper pinned to Maven 3.10.1, Spring Boot 4.1.3. Follow Technical Decisions and AD-1 in the Architecture Spine. Temporal owns orchestration, domain uses only JDK types, and Workflow implementations use only replay-safe code, domain types and Activity contracts. Credentials come from the environment. Default to loopback and expose actuator health.

**Never:** Add review operations, live provider or Temporal wiring, Git tool execution, a database or fixture implementation in this ticket.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| Local boot | No DeepSeek key or running services | Spring starts and GET /actuator/health returns 200 with UP | Startup test fails on missing configuration or connectivity requirements |
| Empty controller | POST /api/reviews | No review is started and the response is 404 | Spring handles the unmapped operation |
| Configuration | Operator supplies a non-secret review setting | Typed review settings reflect the override | Spring rejects invalid typed values |

</frozen-after-approval>

## Code Map

- `docs/implementation-plan.md` and GitHub issue #1 define bootstrap as the first feature with no predecessor.
- `_bmad-output/planning-artifacts/epics.md` maps FEATURE-001 to Story 1.1. `ARCHITECTURE-SPINE.md` in the planning architecture directory defines package dependencies, stack pins and the `review.*` tree.
- `README.md` describes the planned system. Preserve that context and add the commands for the bootstrap.
- `docs/architecture.md` and `docs/adr/README.md` describe the logical layers. Map the issue's API, agent and infrastructure roles to the spine's adapter packages and record the bootstrap decisions there.
- `.gitignore` already ignores IDE files and rendered BMAD workflows. No Maven build, Java sources or Actions workflow exist. Existing local Maven defaults to Java 27, so select Temurin 25 explicitly.

## Tasks & Acceptance

**Execution:**

- [x] `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` — create the single module and generate the official script-only wrapper with the pinned distribution checksum. Enforce Java 25. Manage Temporal 1.41.1 and LangChain4j 1.24.0 through BOMs, JGit 7.8.1.202611041320-r explicitly, and ArchUnit 1.5.1 for tests. Resolve the SDK artifacts during verification and inspect the actual Jackson dependency tree.
- [x] `src/main/java/dev/denrzv/codereview/` — add the Boot entry point, typed configuration and the empty review controller in `adapter.api`. Declare the remaining architecture packages with package documentation. Reserve dedicated Workflow implementation and Activity contract packages to enforce their dependency rules separately.
- [x] `src/main/resources/application.yaml` and `.gitignore` — set loopback, health exposure and non-secret review defaults for model, budget, limits, allowlist, prices and output. Ignore local dotenv and generated build and review outputs.
- [x] `src/test/java/dev/denrzv/codereview/` — add startup, health, empty-controller and configuration binding checks. Add ArchUnit checks for layer dependencies and use test fixtures to show that non-JDK domain dependencies and Workflow adapter imports fail.
- [x] `.github/workflows/ci.yml` — run Maven Wrapper verification with Temurin 25 on GitHub Actions for pushes and pull requests without external credentials.
- [x] `README.md`, `docs/architecture.md`, `docs/adr/README.md`, `docs/compatibility.md` — document commands, package mapping, resolved versions and observed verification. Record use of Maven Enforcer, ArchUnit and the repository's Java style without introducing an automatic formatter.

**Acceptance Criteria:**

- Given a clean checkout and Temurin 25, when CI or an operator runs `./mvnw verify`, then Maven 3.10.1 resolves, Java 25 compiles the application, and all tests and packaging pass.
- Given a non-JDK domain dependency or a Workflow import of a concrete adapter, when architecture verification runs, then it fails and identifies the dependency.
- Given the README and a clean environment, when an operator follows setup, then the executable starts and serves health without provider credentials or a Temporal server, and the documented configuration uses only an environment placeholder for the key.

## Implementation Notes

- Temurin 25.0.5.1 and Maven 3.10.1 passed clean verification with 27 tests. The packaged JAR returned 200 UP for health and 404 for review submission without a key or Temporal service. Maven Enforcer rejected Java 21. The resolved build aligns Jackson 2 at 2.23.0 and uses Jackson 3.1.8 for Spring MVC.

- Review added connector binding, packaged JAR smoke coverage, strict settings validation and direct Workflow checks. The CI smoke step passed locally. GitHub Actions has no run for this local commit.

## Spec Change Log

## Review Triage Log

| Finding | Verdict | Route and evidence |
| --- | --- | --- |
| Blind: missing ZIP extractor | medium | Patch. The upstream tarball fallback fails against the pinned ZIP digest, so Unix instructions declare unzip. |
| Blind: Workflow clock and random calls | medium | Patch. The package rule allowed nondeterministic calls on allowed types. Focused call rules cover the direct cases. |
| Blind: Workflow in the wrong package | medium | Patch. The strict rule only covered its designated package. Verify placement of annotated Workflow implementations. |
| Blind: unknown configuration keys | medium | Patch. Spring ignored the misspelled cost key and retained the higher default. |
| Blind: invalid AD-9 limits | medium | Patch. Numeric type binding allowed negative caps and values above the contract maxima. |
| Blind: malformed or missing model pricing | low | Rejected for bootstrap. The application has no cost consumer and starts without provider wiring. Cross-field pricing policy belongs where priced execution is introduced. |
| Blind: incomplete or duplicate allowlist entries | low | Rejected for bootstrap. There is no repository lookup and the default list is empty. Repository validation belongs where a configured ID is first resolved. |
| Blind: loopback assertion | medium | Patch. A wildcard connector also answers an HTTP request to the loopback address. |
| Blind: executable JAR in CI | medium | Patch. Spring test-classpath startup succeeds independently of the repackaged artifact. |
| Edge: required cancellation option | medium | Patch. The checked SDK enum was missing and a compliant fixture demonstrably failed. |
| Edge: Instant.now passes | medium | Patch. The Workflow package allowlist permitted the verified wall-clock call. |
| Edge: cold install without unzip | medium | Patch. The Maven tarball fails ZIP checksum verification. |
| Edge: Windows launch syntax | low | Patch. A Command Prompt launch needs Windows path and variable syntax. |
| Edge: negative limit | medium | Patch. Verified startup accepted a negative integer as a limit. |
| Edge: selected model without price | low | Rejected for bootstrap. There is no provider request or price consumer, and binding that override has no current effect beyond exposing configuration. |
| Verification: effective bind | medium | Patch. Existing HTTP assertions are identical for a loopback and wildcard listener. |
| Verification: executable smoke | medium | Patch. Disabling repackaging produced an artifact with no main manifest attribute while non-network tests passed. |
| Verification: independent allocation checks | medium | Patch. Removing the SINGLE guard and sequence output constraint left the existing budget tests green. |

## Verification

**Commands:**

- `./mvnw -v` and `./mvnw -B clean verify` with Temurin 25 and `DEEPSEEK_API_KEY` unset should show the pinned toolchain and a successful test suite.
- `./mvnw dependency:tree` should show resolved SDK versions and compatible Jackson versions.
- Start the packaged JAR and query `/actuator/health` to confirm the documented startup path.
