# LangGraph4j + Spring AI runtime

This is the original uploaded LangGraph4j application, retained as its own Maven module and refactored to consume `retail-agent-core`.

## Run and test

From the workspace root:

```bash
mvn -pl retail-agent-langgraph4j -am test
mvn -pl retail-agent-langgraph4j -am spring-boot:run
```

The Spring Boot application context wires a `RetailAgent` bean. The existing tests invoke the agent directly. The default LLM provider is simulated (`APP_LLM_PROVIDER=simulated`). For live OpenAI calls set `APP_LLM_PROVIDER=real` and `OPENAI_API_KEY`.

Other settings are configured in `src/main/resources/application.yml`:

- `APP_LLM_SIMULATION=scripted|replay`
- `APP_LLM_RECORD=true|false`
- `APP_LLM_EMBEDDINGS=hash|real`
- `OPENAI_MODEL` for the OpenAI chat model

Fault injection is configurable through `app.llm.faults`. The simulator stack supports scripted replies, recording/replay, and injected timeout, malformed JSON, empty response and low-confidence cases. Cassette replay tests fail loudly on a miss; cassettes are intentionally not fabricated or committed as recorded live responses.

## Agent boundaries

- `RetailAgent` defines graph nodes and conditional transitions.
- `RetailState` is graph working memory. Custom records stored in it are serializable because LangGraph4j snapshots the state between nodes.
- `CandidateEvaluator` independently assigns candidate scores; it does not use model self-confidence.
- `EvidenceVerifier` is authoritative for whether a current in-stock catalogue observation satisfies the original request.
- `Tools` owns the closed action whitelist.
- `IntentionGovernor`, `TypedRecoveryPolicy`, `AdaptiveMemoryPolicy` and `BoundedWorkspace` come from the framework-neutral shared core.
- Planning rejection can return to retrieval/proposal; execution feedback can start a new decision cycle.

For the shared architecture, paper-alignment boundaries, comparison limitations and production gaps, see the workspace root README and `docs/`.

## Persistence limitation

The project does not configure a durable LangGraph4j checkpoint store. Serializable state enables snapshot/copy operations; it does not by itself provide durable restart/resume across process loss.
