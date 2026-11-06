# Phase 3 delivery — CoALA memory and evidence boundaries

## Delivered

1. Added framework-independent typed memory models for working snapshots, episodes, semantic facts, procedures, use events and per-use outcome attribution.
2. Added explicit lifecycle states and transition restrictions.
3. Added subject-scoped repository ports and a deterministic in-memory implementation for all CoALA memory kinds.
4. Added a subject-scoped evidence repository. Verified episode recording, semantic activation, procedural validation and causal attribution verify existence, freshness, subject scope and evidence authority. Evidence IDs are immutable within each subject scope.
5. Added a PostgreSQL JDBC implementation for the complete memory/evidence repository set and idempotent schema bootstrap script `V003__coala_memory.sql`.
6. Added production-profile guardrails requiring `app.memory.backend=postgres`.
7. Migrated the primary LangGraph4j runtime to `CoalaMemoryService`; the graph now fans out episodic, semantic, procedural and catalogue retrieval as separate branches.
8. Added workspace exposure records for admitted memories. Usage events, episodic appends, evidence inserts and attribution writes are replay-idempotent (while conflicting ID reuse is rejected); the memory kind participates in deterministic usage identity. Overall task success is no longer used to reward every memory/workspace candidate.
9. Repeated procedure validation evidence does not inflate success counts; a newly evidenced failure archives an active reusable procedure until a replacement candidate is validated. Procedure updates use compare-and-set with bounded retry; episode and semantic state changes use compare-and-set; semantic replacement locks the superseded PostgreSQL row.
10. Database schema constraints guard enum values, confidence range, validity/expiry intervals and nonnegative validation counters.
11. Kept the original `EpisodicMemory` compatibility class and the entire `retail-agent-langchain4j` experimental module unchanged.

## Verification performed

- Compiled every `retail-agent-core` production source with `javac --release 21 -Xlint:all` after the final memory-use/evidence immutability changes.
- Executed a standalone Java smoke test covering subject-scoped semantic activation, rejection of workspace-exposure causal attribution, evidence-backed attribution for an explicitly cited fact, working-memory optimistic revision updates and repeated procedure validation. Result: PASS.
- Added JUnit coverage for replayed usage-event idempotency and evidence-ID immutability. The JUnit suite itself remains unexecuted because Maven cannot be downloaded here.
- Static inspection of the graph's four retrieval branches and their distinct `RetailState` output keys.
- Parsed/validated POM XML, YAML and SQL structure during packaging; ZIP integrity checked after creation.
- Compared `retail-agent-langchain4j/` against Phase 2 source archive; no file changes are permitted and none are intended.

## Not verified in this environment

- Full Maven reactor/JUnit run: Maven distribution and dependency artifacts are not available; network resolution of the Maven distribution host fails in this environment.
- Spring Boot application-context startup and JDBC adapter compilation against the pinned Spring Boot 4.1 dependency graph.
- PostgreSQL integration, migration execution, transaction rollback and concurrent compare-and-set behavior against a live server.
- Cross-instance subject/thread authorization: API authentication/authorization is still a later production-hardening phase.

## Acceptance follow-up

Run `./mvnw -B -ntp clean verify` on a Java 21 runner with Maven Central access. Start PostgreSQL, set `APP_MEMORY_BACKEND=postgres`, `APP_GRAPH_CHECKPOINT_BACKEND=postgres`, configure the matching JDBC/checkpoint credentials, and run integration tests for persistence/restart, owner isolation, supersession rollback, duplicate attribution and working-memory CAS conflicts before claiming the persistence path verified.
