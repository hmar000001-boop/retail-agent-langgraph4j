# Phase 2 — Primary LangGraph4j runtime

Status: **implementation and test sources delivered; Maven/JUnit/PostgreSQL execution not verified in this environment**.

## Scope delivered

1. Refactored the primary path into a thin `RetailAgent` facade, `RetailAgentGraph` topology/thread lifecycle owner, and `RetailGraphNodes` application node operations.
2. Added a planning subgraph (`propose -> evaluate -> select`) and one parallel retrieval fan-out/fan-in stage. Episodic and catalogue branches write different LastValue state keys; the append-only observation/trace channels are not written by both parallel branches.
3. Replaced the primary real chat provider path with LangChain4j `ChatModel.chatAsync(ChatRequest)`. Spring AI remains for the current embedding/vector-store adapter and deterministic test compatibility.
4. Preserved scripted/fault-injection behaviour and opt-in LangChain4j cassette recording/replay.
5. Added configurable checkpoint saving: synchronized in-memory saver for local/test use and LangGraph4j PostgreSQL V1 saver for a durable checkpoint store. The `production` Spring profile rejects the memory backend.
6. Added explicit approval interrupt/resume: owner check, checkpoint pending-status check, state update, `GraphInput.resume()`, and execution-time action-registry revalidation. A pending result now exposes an immutable `PendingActionApproval` containing the exact action description, kind, risk, effects, destructive/irreversible/financial/external-sharing flags, arguments, rationale, subject and intention revision. Resume requires the matching approval ID as well as the authenticated thread owner; stale/mismatched IDs are rejected. The execution node compares the live registry definition and selected arguments with the checkpointed request immediately before gateway invocation. The simulator gateway independently rejects actions that require approval.
7. Enforced the default policy that every non-`READ` effect requires approval, in addition to external, high/critical risk, destructive, irreversible, financial and external-sharing actions. Empty effect declarations are rejected; callers must explicitly declare `READ` for read-only actions.
8. Added fixed striped in-JVM lifecycle locks to serialize same-thread starts/approvals without an unbounded lock map.
9. Removed the observation-history bypass around bounded workspace prompt admission.
10. Made model-call budget usage invocation-local, including the optional structured-output repair call. This prevents concurrent customer threads charging one another's graph budgets.
11. Made simulator idempotency atomic per idempotency key and replaced weak Java `hashCode()` fingerprints with SHA-256 identifiers.
12. Added tests for approval execution boundaries, stale-authority revalidation, end-to-end graph interruption/owner-bound resume, approval request contents, stale approval-ID rejection and denial, duplicate graph-thread creation, cassette recording, and non-finite uncertainty rejection.

## Configuration

- `APP_LLM_PROVIDER=simulated|real`; real chat uses LangChain4j OpenAI.
- `APP_LLM_RECORD=true` is explicitly opt-in and writes full prompts/responses into local cassette files; these files are git-ignored because they may contain sensitive context.
- `APP_GRAPH_CHECKPOINT_BACKEND=memory|postgres`; memory is not durable.
- `APP_GRAPH_CHECKPOINT_HOST`, `PORT`, `DATABASE`, `USERNAME`, `PASSWORD`, `CREATE_TABLES` configure the PostgreSQL saver.
- `compose.phase2.yml` starts PostgreSQL 16.6 for local integration testing. The password must be supplied through the environment.

## Verification in this execution

- Java runtime: 21.0.12 available.
- All `retail-agent-core` production sources compile with `javac --release 21` after the final edits.
- All Maven POM files parse as XML.
- The Maven Wrapper was invoked. It stopped before Maven startup because this environment cannot resolve `downloads.apache.org` (DNS/network unavailable). Consequently the Maven reactor, JUnit tests, Spring context, real LangChain4j/OpenAI path, and PostgreSQL saver were **not executed locally**.
- CI contains a full-reactor test job and a second job that starts PostgreSQL and runs the LangGraph4j test suite with the PostgreSQL backend. Inspect the CI run before treating those paths as verified.
- `retail-agent-langchain4j/` was compared against the Phase 1 delivery and is unchanged byte-for-byte. It remains the experimental Agentic comparison track, as requested.

## Deliberately not claimed complete

- No REST API/authentication layer; `authenticatedSubjectId` must be supplied by a trusted caller derived from its verified security principal. Never trust a request-body or model-provided subject ID.
- The in-JVM striped locks do not coordinate multiple application instances. Deploy as a single writer until Phase 8 adds database-backed concurrency/idempotency controls.
- PostgreSQL checkpoint wiring is source-integrated but not locally integration-tested. It uses the LangGraph4j 1.8 V1 saver; its interruption metadata and cross-restart approval-resume behavior require an integration test. A V2 saver migration belongs with the planned LangGraph4j version compatibility work.
- Durable episodic/semantic/procedural memory and intention storage are not delivered here; only graph checkpoints are configured for PostgreSQL.
- Workspace admission scores and adaptive-memory maps remain in-process.
- Current RAG remains Spring AI `SimpleVectorStore` with hash embeddings by default; durable hybrid retrieval and reranking remain later phases.
- LangChain4j JSON Schema structured outputs, dynamic `ToolProvider`/MCP, typed streaming and model routing remain later phases. Current model JSON is parsed into typed DTOs and independently validated by Java policy.
- This phase does not make the whole project production-ready. Authentication, multi-tenant isolation, transactional external-side-effect idempotency, durable audit and operational controls remain open production gates.

## Primary references used for the integration

- [LangGraph4j 1.8 core: state schema, reducers, checkpointing and interrupts](https://langgraph4j.github.io/langgraph4j/1.8/core/core-library/)
- [LangGraph4j 1.8: parallel fork/join branches](https://langgraph4j.github.io/langgraph4j/1.8/how-tos/parallel-branch/)
- [LangGraph4j 1.8: state-graph subgraphs](https://langgraph4j.github.io/langgraph4j/1.8/how-tos/subgraph-as-stategraph/)
- [LangGraph4j 1.8: wait for user input and resume](https://langgraph4j.github.io/langgraph4j/1.8/how-tos/wait-user-input/)
- [LangChain4j: asynchronous ChatModel API](https://github.com/langchain4j/langchain4j/blob/main/langchain4j-core/src/main/java/dev/langchain4j/model/chat/ChatModel.java) and [OpenAI implementation of native `doChatAsync`](https://github.com/langchain4j/langchain4j/blob/main/langchain4j-open-ai/src/main/java/dev/langchain4j/model/openai/OpenAiChatModel.java)
