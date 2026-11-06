# Verification, test strategy and known limitations

## Verification status — Phase 2 source revision (2026-11-06)

This section describes the Phase 2 source tree in this archive. It supersedes older test-status snapshots in this file and in historical project notes.

Verified in the packaging environment:

- OpenJDK 21.0.12 is available.
- All shared-core production Java sources compile with `javac --release 21`.
- Every Maven POM parses as XML.
- `application.yml` and `compose.phase2.yml` parse as YAML.
- The experimental `retail-agent-langchain4j/` module is byte-for-byte identical to the Phase 1 delivery and was not changed.
- The generated archive passes `unzip -t`.

Not verified in this environment:

- The Maven Wrapper was invoked but stopped before Maven startup because this environment cannot resolve `downloads.apache.org`. The Maven reactor, JUnit suite, Spring application context, primary real LangChain4j/OpenAI calls, and PostgreSQL saver were therefore not executed here.
- CI definitions include a full-reactor job and a PostgreSQL-backed LangGraph4j job, but no CI result is bundled with this delivery. A green workflow run is still required.

Do not interpret source compilation or static configuration parsing as proof that all integration tests pass.

## Phase 2 tests added or retained

### Shared domain contracts

- Action effects must be explicitly declared; an empty effect list is rejected rather than assumed read-only.
- A non-`READ` action requires human approval by default, including a low-risk internal `WRITE`.
- External, high/critical-risk, destructive, irreversible, financial and external-sharing actions require approval.
- A low-risk internal `READ` action can execute without definition-level approval.
- Evidence freshness is explicit; model confidence cannot authorize a verified finish.
- Workspace admission is bounded; action invocations are immutable and require a subject and idempotency key.

### Primary LangGraph4j path

- Scripted model loop, catalogue behavior, fallback/fault paths and LLM stack selection.
- Cassette record/replay and replay misses.
- Planning subgraph and parallel retrieval fan-out/fan-in.
- Approval execution boundary, stale-authority revalidation, authenticated thread owner check, typed checkpointed approval request (action/risk/effects/arguments/rationale/subject/intention), interrupt/resume using a matching approval ID, stale-ID rejection and denial-without-execution.
- Workspace re-admission of tool output before reflection.
- Duplicate graph-thread creation and model-call accounting behavior.

### Experimental LangChain4j Agentic path

The module is intentionally unchanged in Phase 2. Its earlier source/test observations remain historical; the Phase 2 packaging environment did not run its JUnit suite. This module remains an experimental comparison implementation, not the primary runtime.

## Remaining architecture and production limitations

1. **Authentication boundary:** There is no REST/API security layer in this phase. `authenticatedSubjectId` must be passed only by a trusted caller derived from a verified security principal. Never trust a request-body or model-provided subject ID.
2. **Multi-instance coordination:** The fixed striped locks serialize same-thread operations only inside one JVM. They do not coordinate multiple application instances. Use a single writer until Phase 8 adds distributed concurrency controls.
3. **PostgreSQL checkpoint semantics:** PostgreSQL V1 saver wiring is source-integrated but not locally integration-tested. Persistence of approval interruption metadata and cross-process approval resume require an explicit integration test. Do not infer restart-safe approval resumption merely from checkpoint persistence; assess a V2 saver/migration as a separate compatibility task.
4. **Memory persistence:** Episodic, semantic and procedural memory stores, memory lifecycle/consolidation, durable intention storage and cross-session learning are not delivered here. The graph checkpoint does not replace those stores.
5. **Retrieval:** The default remains Spring AI `SimpleVectorStore` with hash embeddings. Durable hybrid retrieval, metadata isolation, reranking, source freshness and retrieval evaluation remain later phases.
6. **LLM features:** Primary chat uses LangChain4j asynchronous `ChatModel` calls and typed Java DTO parsing/validation. JSON Schema constrained output, dynamic `ToolProvider`/MCP, typed streaming and model routing remain later work.
7. **Budgets:** Model-call accounting counts logical LangChain4j gateway invocations (including the structured-output repair call), not every underlying HTTP retry, token, monetary cost or provider-side operation.
8. **Action infrastructure:** The current catalogue and action gateway are deterministic local simulator components. Real inventory/order systems, transactional outbox, externally durable idempotency, audit retention and compensation are not present.
9. **Security hardening:** Prompt-injection isolation, tenant partitioning, secret management, retention controls, rate limits, deadlines and operational audit are incomplete.
10. **Recovery and learning:** Recovery is not yet a separately compiled isolated child graph with typed resolution compilation; adaptive memory utility remains heuristic and process-local. These belong to later phases.

## CI acceptance gates

Before accepting Phase 2 as integrated:

- Run `./mvnw -B -ntp clean verify` on a runner able to resolve Maven Central and the configured wrapper distribution.
- Run the PostgreSQL-backed LangGraph4j job using `compose.phase2.yml` or an equivalent PostgreSQL service.
- Confirm `RetailAgentApprovalResumeTest` pauses before executing the action, exposes the exact typed action request, rejects a different authenticated subject and stale approval ID, resumes successfully for the owner with the matching ID, and denies without invoking the gateway.
- Verify all Agentic module files remain unchanged from the Phase 1 delivery.

Before production:

- Implement the authenticated API boundary and derive customer identity only from verified credentials.
- Test checkpoint and approval resume through process restart and across application instances.
- Add distributed locking/optimistic concurrency, durable action idempotency and a transactional outbox for real side effects.
- Add durable memory/intention stores, tenant-isolated retrieval and audit.
- Calibrate uncertainty on held-out labelled tasks and run mechanism ablations with deterministic scenario data.


## CI failure follow-up (2026-11-06)

A GitHub Actions run reported two unresolved `CLARIFY` references in `RetailGraphNodes.java` (lines 305 and 633 in that checkout). Both the full `clean verify` job and PostgreSQL checkpoint test job failed at the same Java compilation step. The Phase 3 corrective source now defines the missing prompt constant at the node-operation boundary and adds a focused regression test for the clarification route. A local Maven rerun could not be performed because the wrapper distribution host `downloads.apache.org` is not resolvable from this environment; therefore this correction is not yet marked CI-verified.
