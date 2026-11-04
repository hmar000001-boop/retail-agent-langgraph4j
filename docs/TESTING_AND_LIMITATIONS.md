# Verification, test strategy and known limitations

## Verification status of this ZIP

The first user-provided CI run established the following before stopping in LangGraph4j:

- Shared core: 9 tests passed, including the corrected floating-point tolerance test.
- LangGraph4j: 18 tests were executed; 2 failed and 1 errored.
- LangChain4j module: skipped because Maven stopped at the failing LangGraph4j module.

The LangGraph4j failures were traced to three causes: Spring constructor binding for a record with an additional convenience constructor; repeated node visits using duplicate trace labels; and an outdated outage-test assertion that hard-coded `p-103` for a query that did not require waterproof footwear. This revision addresses those causes. The packaging environment has no Maven installation and cannot resolve Maven Central, so the corrections **still require a fresh CI run**. Do not treat the corrected source as a green build until `mvn -B -ntp clean verify` passes.

## Tests in the repository

### Shared core

- Model self-confidence cannot authorize `finish`.
- Current stock, observed price, product attributes and user constraints must all match before the goal is satisfied.
- Bounded workspace rejects content that exceeds its token budget.
- Intention method may change while committed goal remains unchanged.
- `Proposal` round-trips through Java serialization for LangGraph4j state cloning.
- Adaptive memory refresh preserves learned activation/downstream value while refreshing content and cost metadata.
- Both adapters use the same default `AgentLimits` resource envelope.

### LangGraph4j

The uploaded test suite is retained and adapted to the shared core: LLM stack configuration, catalogue search, scripted agent loop, contract behavior, cassette record/replay, replay misses, low-confidence/fault behavior and fault-injection semantics. The simulator uses the provider abstraction and supports scripted replies, recording/replay and injected timeout/malformed/empty/low-confidence cases. Trace events now include cycle/planning-round coordinates so repeated node executions are observable and testable.

### LangChain4j Agentic

- Scripted `ChatModel` drives the custom planner through retrieval, proposal, independent evaluation, selection, tool execution, reflection/monitoring, learning and final answer.
- The test checks that a stock observation is required before recommendation.
- The test calls the same runtime twice to guard against a stale terminal planner cursor leaking into a new request.

The LangChain4j simulator is currently scripted-only; it does not yet have the LangGraph4j module's cassette recorder/replayer or full fault-injection suite.

## Important comparison limitations

1. **Retrieval differs.** LangGraph4j uses Spring AI vector search; LangChain4j uses deterministic lexical catalogue search. Do not compare answer quality as a framework result until the retrieval adapter is unified.
2. **Model call handling differs.** LangGraph4j has retries, provider fallback and fault simulation. LangChain4j's current scripted adapter tests orchestration; provider-error resilience and cassette parity remain follow-up work.
3. **State durability is not configured.** LangGraph4j has no durable checkpoint store in this sample. LangChain4j has no configured `AgenticScopeStore`; the Planner's execution-state methods alone do not persist across process loss.
4. **Learning is heuristic and process-local.** There is no offline/online statistical calibration or persistence of learned utility values.
5. **B2 compilation is intentionally absent.** Recovery outputs are not automatically converted into reusable skills.
6. **No production integrations.** Catalogue, order history and stock are in-memory fixtures, and there is no payment, ordering or external side effect.

## Suggested next acceptance gates

Before benchmark:

- Run the complete Maven build and both modules' tests.
- Use one retrieval implementation and a shared set of deterministic prompts/scenarios.
- Add adversarial simulator cases: premature `finish`, unsupported tool name, invalid product ID, contradictory stock, stale price, low model confidence with correct action, high confidence with wrong action, provider timeout and malformed output.
- Assert both *state outcomes* and *trace transitions*, not just answer text.
- Record budget consumption and ensure every loop has a finite bound.

Before production:

- Add a durable checkpoint/AgenticScope store and test process restart/resumption.
- Add authenticated customer scoping, tool idempotency, external inventory source-of-truth and audit logs.
- Calibrate uncertainty from held-out labelled outcomes and define risk thresholds.
- Test recovery parent-state invariants and prove that any future compilation is safe before enabling it.

## CI dependency-resolution follow-up (2026-11-04)

The latest observed GitHub Actions run verified:

- Shared core: 9 tests passed.
- LangGraph4j + Spring AI: 18 tests passed, including fault-injection, scripted-agent, Spring application-context, cassette, catalogue-search and LLM-stack tests.
- The LangChain4j module was not compiled or tested because Maven Central could not resolve the requested coordinate `dev.langchain4j:langchain4j-agentic:1.23.0`.

The Agentic artifact is independently versioned from the main LangChain4j artifacts. The pinned compatible pair in this workspace is:

- `dev.langchain4j:langchain4j-agentic:1.22.0-beta32`
- `dev.langchain4j:langchain4j-open-ai:1.22.0`

The published Agentic POM declares `dev.langchain4j:langchain4j:1.22.0`. The root POM now exposes separate `langchain4j.version` and `langchain4j.agentic.version` properties. This fixes the missing-artifact coordinate; the next CI run must still compile and execute the LangChain4j module before the full reactor can be called green.
