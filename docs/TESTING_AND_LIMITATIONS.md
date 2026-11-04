# Verification, test strategy and known limitations

## Verification status of the current source revision

The latest user-provided GitHub Actions run established:

- Shared core: **9 tests passed**.
- LangGraph4j + Spring AI: **18 tests passed**; the module compiled, its Spring application context loaded, and the scripted, fault-injection, cassette, catalogue and LLM-stack tests passed.
- LangChain4j Agentic: production and test sources **compiled**; 4 tests ran, with 3 passing and 1 failing.

The remaining LangChain4j failure is in `LangChainRetailRuntimeTest.plannerRunsRetrieveProposeEvaluateSelectExecuteReflectAndAnswer`. The planner did invoke the model-backed proposal agent twice, as also shown by the scripted model's expected call count, but the transition trace omitted an explicit `propose` event. The trace contained `budget:model:proposal=reserved`, then `evaluate`, which made the test unable to distinguish reservation from actual model invocation. The current source wraps the proposal model call in a named non-AI trace adapter that writes `propose` immediately before invoking the model, and strengthens the test to assert exactly two proposal events.

This environment cannot run the complete Maven reactor, so the trace correction **still requires a fresh CI run**. Do not treat the current source as fully green until `mvn -B -ntp clean verify` passes.

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

## CI follow-up (2026-11-04)

The latest CI run confirms that the independently versioned LangChain4j dependency pair resolves successfully:

- `dev.langchain4j:langchain4j-agentic:1.22.0-beta32`
- `dev.langchain4j:langchain4j-open-ai:1.22.0`

It also confirms that the root reactor, shared core and LangGraph4j module pass. The remaining failure is only the missing LangChain4j `propose` trace event; it is corrected in the current source by wrapping the proposal model invocation in a non-AI trace adapter. The full reactor must be rerun before declaring the workspace green.
