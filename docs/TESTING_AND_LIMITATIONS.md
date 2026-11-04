# Verification, test strategy and known limitations

## Verification status of this ZIP

The workspace was assembled and statically inspected in the packaging environment. The JDK 21 compiler was available, but Maven was not installed and outbound access to Maven repositories was unavailable. Consequently:

- The shared core sources were checked with `javac --release 21`.
- XML structure of all Maven POM files was checked.
- ZIP integrity is checked after packaging.
- **The full Maven reactor, Spring context, LangGraph4j graph, LangChain4j Agentic planner and JUnit suites have not been executed here.** Run `mvn -B -ntp clean verify` on a Maven-enabled machine.

Do not interpret this package's source review as a green CI/build result.

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

The uploaded test suite is retained and adapted to the shared core: LLM stack configuration, catalogue search, scripted agent loop, contract behavior, cassette record/replay, replay misses, low-confidence/fault behavior and fault-injection semantics. The simulator uses the provider abstraction and supports scripted replies, recording/replay and injected timeout/malformed/empty/low-confidence cases.

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
