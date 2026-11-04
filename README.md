# retail-agent

Retail assistant (search, history, recommendations) built with **Spring AI 2.0** (models, vector store) and
**LangGraph4j 1.8** (agent loop), with a test-time flag that swaps the real OpenAI model for a simulated one.

> Status: written without access to Maven or the network, so **it has not been compiled or run yet**.
> First step: `mvn -B verify`. See "Things to verify" at the bottom.

## The flag

| What you want | Command |
|---|---|
| Simulated LLM (default, CI) | `mvn verify` |
| Real OpenAI | `OPENAI_API_KEY=... mvn test -Dapp.llm.provider=real` |
| Real OpenAI and record cassettes | `... -Dapp.llm.provider=real -Dapp.llm.record=true` |
| Replay cassettes (deterministic, offline) | `mvn test -Dapp.llm.simulation=replay` |
| Inject faults | `app.llm.faults` list in YAML (see `application.yml`) |

Env equivalents: `APP_LLM_PROVIDER`, `APP_LLM_RECORD`, `APP_LLM_SIMULATION`. Values are lowercase.
`LlmStack` is the one class that turns the flag into a `ChatModel`.

## Simulation strategies (all are plain Spring AI `ChatModel`s)

1. **Scripted** (`ScriptedChatModel` + `RetailScriptedResponder`): rules matched on the prompt's `[TASK:X]` tag and its
   QUERY / OBSERVATIONS sections. No matching rule throws, so a prompt change breaks a test instead of passing silently.
2. **Record & replay** (`RecordingChatModel`, `ReplayChatModel`, `CassetteStore`): real calls are saved as readable
   files in `src/test/resources/cassettes`; replay returns them and fails loudly on a miss.
   Embeddings are always the local hash model when recording, otherwise replayed prompts would differ.
3. **Fault injection** (`FaultInjectingChatModel`): decorator over any of the above. Kinds: `timeout`, `malformed_json`,
   `empty`, `low_confidence`.

## Agent design (from the papers)

CoALA (2309.02427) gives the structure; the 2026 review (2607.23942) gives the governance rules.

| Paper concept | Here |
|---|---|
| Working memory | `RetailState` (LangGraph4j state) |
| Semantic memory | Spring AI `VectorStore` over the catalog |
| Episodic memory | `EpisodicMemory`, written by the `learn` node |
| Retrieval action | `retrieve` node, re-run every cycle (interleaved with reasoning) |
| Reasoning actions | `propose`, `evaluate`, `reflect` (LLM) |
| Grounding actions | `Tools`: closed whitelist, `get_history` is pinned to the authenticated customer |
| Decision procedure | graph loop: propose -> evaluate -> select -> execute -> reflect -> retrieve ... |
| Control kept outside the LLM | `select` is plain code: it can clarify, abort, or refuse to finish before any evidence exists |
| Typed failure and degradation | `LlmFailure` -> deterministic fallbacks (`propose:fallback`, ...) |
| Uncertainty and budget | `clarify-threshold`, `max-iterations`, `max-failures` |

## CI

`.github/workflows/ci.yml`: every push and PR runs `mvn verify` with the simulated LLM (no secrets needed).
A manual run with `llm_provider=real` runs the same contract test against OpenAI using the `OPENAI_API_KEY` secret,
optionally recording cassettes and uploading them as an artifact.

## Tests

- `ScriptedAgentTest`, `FaultInjectionAgentTest`, `CassetteRoundTripTest`: no Spring context, fast.
- `LlmStackTest`: the flag semantics.
- `RetailAgentContractTest`: Spring context, **same assertions for simulated and real**.
- `RetailAgentReplayTest`: skipped until cassettes exist.

## Things to verify first

- **Versions**: Spring Boot `4.1.0` (parent), Spring AI `2.0.1`, LangGraph4j `1.8.25`. Spring AI 2.0 needs Boot 4 and Java 21.
- **Step limit**: one loop cycle is 6 graph steps. I believe LangGraph4j's default limit is 25, hence `max-iterations: 3`.
  To go higher, raise the limit via `RunnableConfig` (check the 1.8 API).
- **Jackson 3** (`tools.jackson`) is used in `LlmClient`; annotations still come from `com.fasterxml.jackson.annotation`.
- **Spring AI API touch points** if the compiler complains: `ChatReplies` (AssistantMessage/ChatResponse construction),
  `HashEmbeddingModel` (EmbeddingModel methods), `AgentConfiguration` (SimpleVectorStore/Document builders).
- **OpenAI model name** defaults to `gpt-4o-mini` (`OPENAI_MODEL`); change it to what your account uses.
- **Bean names / properties**: `spring.ai.model.chat|embedding|image|moderation|audio.*` = `none` is how unused
  Spring AI providers are switched off; confirm for 2.0 if the context fails to start in simulated mode.
