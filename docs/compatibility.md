# FEATURE-001 compatibility

The bootstrap is one Spring Boot executable. The build resolves the provider, Git and Temporal SDKs so later features share a verified dependency baseline.

| Component | Resolved version |
| --- | --- |
| Temurin Java | 25.0.5.1 in local verification, Java 25 required |
| Maven Wrapper scripts | 3.3.4, script-only |
| Maven distribution | 3.10.1 |
| Spring Boot | 4.1.3 |
| Temporal SDK and testing SDK | 1.41.1, via Temporal BOM |
| LangChain4j core and OpenAI adapter | 1.24.0, via LangChain4j BOM |
| Eclipse JGit | 7.8.1.202611041320-r |
| ArchUnit | 1.5.1, test scope |

## Jackson on the resolved classpath

Spring MVC uses Jackson 3.1.8 (`tools.jackson.core`). Temporal's default converter and LangChain4j use Jackson 2 (`com.fasterxml.jackson.core`). Boot initially selected Jackson 2.21.8. The POM overrides Boot's Jackson 2 BOM to 2.23.0, the version declared by the resolved LangChain4j POMs. The final tree contains Jackson 2.23.0 core, databind and JDK datatype modules, with annotations 2.23 shared by both Jackson lines. Local serialization tests round-trip records through Temporal's default converter and LangChain4j's mapper. Wire protocol and Workflow payload compatibility are checked in the respective integration features.

## Observed verification

On macOS ARM64 with Temurin 25.0.5.1 and `DEEPSEEK_API_KEY` unset:

- `./mvnw -v` reported Apache Maven 3.10.1. The wrapper pins the SHA-256 of the downloaded archive, which was also checked against the publisher's SHA-512.
- `./mvnw -B clean verify` passed all 27 tests and produced the executable JAR. The architecture tests demonstrate rejection of a non-JDK domain dependency and a Workflow dependency on the REST adapter.
- `./mvnw -B dependency:tree` resolved the SDKs and the Jackson versions above.
- `./mvnw -B validate` under Java 21 failed at Maven Enforcer with the required range `[25,26)`.
- The executable JAR started with no provider key or Temporal connection. Port 8080 was occupied locally, so the smoke run used `--server.port=0`. On the assigned loopback port, `GET /actuator/health` returned HTTP 200 with `status` set to `UP`, and `POST /api/reviews` returned HTTP 404.
- The packaged-JAR health step from the GitHub Actions workflow also passed locally.
