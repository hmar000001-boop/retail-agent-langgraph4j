# Primary LangGraph4j + LangChain4j runtime

This module is the primary orchestration path. LangGraph4j owns graph state, control flow, checkpointing, interruption and resumption. LangChain4j supplies the production chat-model client. Spring AI remains only as the current embedding/vector-store adapter and deterministic test compatibility layer; it is not the real chat provider in this module.

## Run and test

From the workspace root:

```bash
./mvnw -pl retail-agent-langgraph4j -am test
./mvnw -pl retail-agent-langgraph4j -am spring-boot:run
```

Default settings use the scripted local model and in-memory checkpoint saver. No API key or database is needed for local tests. The in-memory saver is intentionally non-durable and is not suitable for process restart or production deployment.

## Real model

```bash
APP_LLM_PROVIDER=real OPENAI_API_KEY=... ./mvnw -pl retail-agent-langgraph4j -am spring-boot:run
```

`OPENAI_MODEL` defaults to `gpt-4o-mini`. Transport timeout and provider retry settings are controlled by `APP_LLM_TIMEOUT` and `APP_LLM_MAX_RETRIES`. Prompt/response payload logging is disabled in the model builder.

To deliberately capture exchanges for offline replay, set `APP_LLM_RECORD=true`. Cassettes contain the full system and user prompt as well as the model response; they can contain personal, customer, or commercially sensitive data. Keep capture local, review and redact before sharing, and do not commit raw cassettes. Set `APP_LLM_SIMULATION=replay` to replay captured fixtures without network access or a key. Cassette keys are bound to the canonical task-tagged prompt, so prompt changes produce an explicit cache miss.

## Durable graph checkpoints

Local development uses the memory saver by default. To run PostgreSQL for durable checkpointing:

```bash
export APP_GRAPH_CHECKPOINT_PASSWORD='choose-a-local-password'
docker compose -f compose.phase2.yml up -d
export APP_GRAPH_CHECKPOINT_BACKEND=postgres
export APP_GRAPH_CHECKPOINT_PASSWORD='choose-a-local-password'
./mvnw -pl retail-agent-langgraph4j -am test
```

For the Spring `production` profile, the application refuses to start unless `APP_GRAPH_CHECKPOINT_BACKEND=postgres`. Set the database host, port, database, username and password explicitly in a real deployment. The compose file is a local development service, not a production database configuration.

## Graph execution model

- `RetailAgent` is a thin application facade.
- `RetailAgentGraph` owns topology, conditional transitions, thread IDs, saver, interrupt and resume.
- `RetailGraphNodes` contains node/application behavior.
- The inner `planning` subgraph keeps proposal, independent evaluation and selection together.
- Episodic-memory and catalogue retrieval fan out into separate nodes and fan in before workspace admission. They write distinct channels; the append-only trace and observation channels are not written by both retrieval branches.
- Only admitted bounded workspace content reaches the model prompt. Full observation history is not appended around the workspace budget.
- Action authority comes from the injected typed `ActionRegistry` and `ActionGateway`; model output cannot register an action.

## Human approval lifecycle

Actions requiring approval route to the `execute_approved` node, which is interrupted before execution. The returned `AgentResult` exposes `threadId`, `awaitingApproval` and the pending status. The application must bind that thread ID to the authenticated customer/session at its API boundary.

A pending `AgentResult` carries a typed `PendingActionApproval` with the action ID and description, kind, risk, effects, destructive/irreversible/financial/external-sharing flags, exact arguments, rationale, authenticated subject, intention ID/revision and a unique approval ID. Resume through `RetailAgent.resumeApproval(threadId, authenticatedSubjectId, approvalId, approved)`. The runtime checks the saved thread owner and compares the supplied approval ID with the exact request stored in the checkpoint before it records the decision and resumes with `GraphInput.resume()`. The execution node rechecks the live action definition and compares it with the approved snapshot immediately before gateway invocation. The simulator gateway independently rejects actions whose definitions require approval. The shared contract requires approval for every non-`READ` effect by default; low-risk internal reads remain executable without approval. An old or mismatched approval ID is rejected even when supplied by the thread owner.

This is an application-level control boundary, not an authentication system: a real API must derive `authenticatedSubjectId` from its verified security principal, never from a request body or model output. Multi-instance locking, API authentication and durable audit are production-hardening work, not claimed as implemented here.

## Current limitations

- PostgreSQL checkpoint wiring is present, but the integration environment in this delivery could not execute Maven or connect to a real database. CI includes a PostgreSQL-backed verification job; its result must be observed on GitHub before the database path is considered verified. The current 1.8 V1 saver still needs an explicit cross-restart approval-resume test; do not infer that behavior from checkpoint persistence alone.
- Long-term episodic policy and workspace-admission scores remain in-process maps. Durable CoALA memory and outcome attribution are later phases.
- Spring AI `SimpleVectorStore` and hash embeddings remain the retrieval adapter; persistent hybrid RAG/reranking is later work.
- LangChain4j structured JSON Schema output and tool-provider/MCP integration are later phases; this phase keeps model output as validated JSON DTO proposals and Java-owned action authority.
- The experimental `retail-agent-langchain4j` Agentic module is unchanged and remains a separate comparison track.
