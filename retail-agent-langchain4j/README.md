# LangChain4j Agentic runtime

This module implements the retail agent with LangChain4j's **experimental** `langchain4j-agentic` module and a custom `Planner`.

## Design

`LangChainRetailRuntime` builds the Agentic subagents and shared core policies. `RetailPlanner` owns the control flow:

```text
retrieve → reserve model budget → propose → evaluate → select
     ↑                                             │
     └──────────── reject all / replan ────────────┘
                                                   │ execute
                                                   ↓
                           reserve reflection → advisory reflection
                                                   ↓
                                    deterministic evidence monitor
                                      │ continue / recover / learn
                                      ↓
                                 answer budget → answer → finalizer
```

The 14 subagents are intentionally indexed in the same order as `RetailPlanner`'s transition table. If the agent list changes, update the planner and its test together. The proposal model is wrapped by a small non-AI trace adapter: it records `propose` immediately before invoking the model-backed `ProposalAgent`, while the preceding reservation node separately records `budget:model:proposal`. This keeps the trace honest: a budget reservation is not itself a model invocation. The test asserts two proposal events for the two decision cycles. The planner persists its cursor as execution state, and reads its cursor from the current AgenticScope at the start of each invocation so one request cannot inherit the previous request's terminal stage.

## Run

From the workspace root, see the root README. The simulated model is the default:

```bash
mvn -pl retail-agent-langchain4j -am test
mvn -pl retail-agent-langchain4j -am exec:java \
  -Dexec.mainClass=com.example.retail.langchain.RetailAgenticApplication
```

OpenAI:

```bash
OPENAI_API_KEY=... RETAIL_LLM_PROVIDER=openai \
  mvn -pl retail-agent-langchain4j -am exec:java \
  -Dexec.mainClass=com.example.retail.langchain.RetailAgenticApplication
```

## Scope

The shared `CandidateEvaluator` and `EvidenceVerifier` make action selection and goal completion independent of model self-confidence. The model can propose actions, provide advisory reflection and draft an answer; it cannot authorize tools or completion.

`AgenticScope` contains JSON-friendly JDK values rather than custom state objects. This eases JSON persistence configuration, but **no `AgenticScopeStore` is configured in this teaching project**. `Planner.executionState()` / `restoreExecutionState()` expose the cursor needed for a persistence adapter; they are not themselves durable storage.

The module currently has a deterministic scripted `ChatModel`; cassette replay and fault injection are not yet at parity with the LangGraph4j module. Initial catalogue-memory retrieval is lexical here, unlike the vector-search adapter in the LangGraph4j module. The explicit `search_catalog` grounding action is shared deterministic lexical search in both runtimes.

## Dependency compatibility

The Agentic artifact has its own beta version line. This workspace pins `langchain4j-agentic` to `1.22.0-beta32` and the core/OpenAI integration to `1.22.0`, matching the Agentic POM's declared core dependency. These properties are intentionally separate in the root POM.
