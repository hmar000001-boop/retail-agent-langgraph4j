# Phase 0 — Reproducibility and baseline

## Scope delivered

- Added a repository-local Maven launcher for Linux/macOS (`./mvnw`) and Windows (`mvnw.cmd`).
- Pinned Apache Maven 3.10.0 and SHA-512 verification for the distribution archive.
- Enforced JDK 21 and Maven 3.10.0+ at the Maven reactor boundary.
- Standardized CI on the checked-in wrapper, simulated LLM provider mode, bounded job time, and Surefire/Failsafe report collection.
- Added reproducible archive timestamp and compiler parameter metadata.
- Tightened `.gitignore` for Maven outputs, IDE state, secrets, and local cassette artifacts.
- Documented local build commands.

## Explicitly preserved

- `retail-agent-langchain4j` and all its source files/dependencies remain unchanged in this phase. It remains an experimental comparison implementation.
- Existing modules, domain behavior, simulator, replay facilities, tests, and documentation remain in place.

## Verification status

- Static inspection: wrapper properties, launcher scripts, POM and workflow were reviewed.
- Shell syntax: `sh -n mvnw` is run as part of packaging validation.
- Full Maven reactor: **not run in this execution environment**. No Maven installation is present and outbound package downloads are blocked. CI must execute `./mvnw -B -ntp -Dapp.llm.provider=simulated -Dapp.llm.record=false clean verify` before this phase can be marked build-verified.

## Acceptance still outstanding

1. Run the wrapper on Linux and Windows and verify the pinned distribution download/checksum.
2. Run the full Maven reactor on JDK 21 and resolve any pre-existing compilation/test failures.
3. Record the baseline test inventory and successful test results before Phase 1 domain refactoring.

## Next phase

Phase 1: extract stable domain/application contracts, establish typed evidence/action/plan/decision models, and add framework-independent domain tests. No changes to the Agentic comparison module.
