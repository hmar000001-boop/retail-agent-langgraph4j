# Architecture and runtime walkthrough

## 1. Why this is one workspace

The repository is a Maven reactor, not one mixed application. The shared core has no orchestration-framework dependency. Each runtime depends on it independently:

```text
                 retail-agent-core
                 /               \
                /                 \
  retail-agent-langgraph4j   retail-agent-langchain4j
   LangGraph4j + Spring AI    Agentic custom Planner
```

This is the best structure for a later comparison because shared decisions and scenario data do not need to be copied between two codebases. It also makes the framework-specific state and control mechanisms explicit.

## 2. CoALA mapping

| CoALA dimension | Concrete implementation |
|---|---|
| Working memory | `RetailState` in LangGraph4j; `AgenticScope` JDK-valued entries in LangChain4j |
| Episodic memory | `EpisodicMemory`, updated by the learning operation |
| Semantic memory | Product catalogue; vector-store retrieval in LangGraph4j, lexical search adapter in LangChain4j |
| Procedural memory | The action registry and explicit runtime procedures; not a learned skill library |
| Retrieval | Retrieve catalogue/episodic candidates into current context |
| Reasoning | LLM proposal and advisory reflection, plus deterministic evaluation/monitoring |
| Learning | Outcome updates to episodic memory, adaptive-memory metadata and workspace admission scores |
| Grounding | Whitelisted `search_catalog`, `get_history`, `check_stock` actions |
| Decision making | Planning proposal/evaluation/selection and execution/observation in a repeated cycle |

CoALA does not prescribe the specific Java class names or this exact graph topology. The table maps paper concepts to a teaching implementation.

## 3. Two levels of iteration

### Inner planning iteration

```text
retrieve → bounded workspace admission → propose → evaluate → select
                                                  ↑             │
                                                  └── reject ───┘
```

In code, a rejected candidate set updates feedback and returns to retrieval, so the next proposal sees new evidence and the rejection reason. A `finish` proposal without verified current stock is rejected. The evaluator is Java code and does not use `Proposal.confidence` to assign its score.

### Outer decision cycle

```text
execute action → observe result → reflect/monitor
       ↑                              │
       └──── continue / recover ──────┘
```

The observation is incorporated into working memory, after which retrieval and planning may run again. Do not conflate this outer action loop with iteration among proposal, evaluation and selection.

## 4. Runtime transition authority

The LLM can propose an action, explain a result or draft user-facing prose. It cannot:

- invoke an unregistered action;
- assign its own authoritative evaluation score;
- override the catalogue's current price/stock data;
- declare the goal complete without the evidence verifier;
- rewrite the committed intention.

The runtime governor evaluates model output against current state and decides whether to execute, replan, recover, learn or stop. The actual catalogue tools are local fixture operations in this example.

## 5. Fan & Lan runtime state translated into code

The paper's proposed synthesis runtime identifies durable control-state categories such as active intention, bounded workspace, memory, compiled skills, uncertainty, resource budget and active recovery substate. The example makes several of these inspectable:

| Runtime concept | Representation here | Status |
|---|---|---|
| Active intention | `IntentionRecord` or primitive scope keys | Explicit goal, rationale, method, triggers, termination and revision |
| Bounded workspace | `BoundedWorkspace`, `WorkspaceCandidate` | Typed competition under token-cost budget; recipients recorded |
| Memory | `AdaptiveMemoryPolicy`, `EpisodicMemory` | Heuristic activation/value update after downstream use |
| Compiled skills | No compiler | B5 is not presented as an unresolved sixth bundle; no reusable compiled skill graph is claimed |
| Uncertainty | `UncertaintyEstimate` | Provenance is carried; self-confidence remains uncalibrated |
| Resource budget | Counts for model calls, tools, planning rounds and iterations | No provider token/cost estimator |
| Recovery substate | `RecoverySubstate` | Typed failure, parent fingerprint, protected goal, bounded attempt and resolution |

The implementation deliberately keeps authoritative values in Java/runtime state. Prompt strings are not the authoritative source of the intention, allowed tools, budget or verified result.

## 6. Framework differences

### LangGraph4j

- The graph is constructed in `RetailAgent.buildGraph()`.
- LangGraph4j 1.8's `CompileConfig.recursionLimit` is explicitly derived from the application-level budgets; it is an engine-level final guard, not a substitute for the planning/tool/model budgets.
- Nodes implement retrieval, proposal, evaluation, selection, execution, reflection, recovery, learning and answer.
- Conditional edges express replan/continue/recover/finish decisions.
- `RetailState` is the graph working state. Its payload types are serializable because the graph clones state between nodes.
- Spring AI provides chat and embedding integration. The simulator stack is in `com.example.retail.llm`.

### LangChain4j Agentic

- Agentic subagents are assembled in `LangChainRetailRuntime`.
- `RetailPlanner` owns the transitions among 14 subagents. The stages are explicit and tested through `ScriptedLangChainChatModel`.
- `AgenticScope` contains JSON-friendly strings, numbers, booleans, lists, and maps rather than custom Java domain objects.
- `executionState()` / `restoreExecutionState()` expose planner-cursor restoration. For durable recovery the application still needs to configure an `AgenticScopeStore` and persist the associated execution state.
- The Agentic module is experimental; its API can change between versions.

## 7. Shared resource envelope and remaining benchmark differences

`AgentLimits.defaults()` defines the same default envelope for both implementations: 3 outer action cycles, 3 planning rejections, 4 failures, 3 tool calls, 8 model calls, 2 recovery attempts, a 180-token memory selection budget and a 260-token workspace budget. LangGraph4j binds matching values through `AgentSettings`; LangChain4j consumes `AgentLimits` directly.

## 8. Why the frameworks are not yet benchmark-equivalent

The same core domain and policies are shared. The explicit `search_catalog` grounding action uses the shared deterministic lexical catalogue search in both modules, while initial memory retrieval differs: Spring AI vector search in the LangGraph4j version and deterministic lexical search in the LangChain4j version. The model abstractions, prompts, retries and test harnesses also differ. This workspace is useful for understanding orchestration and control semantics; it is not yet a controlled throughput/quality benchmark. A fair benchmark should use one retriever implementation, equivalent prompts, identical model settings, matched timeouts/retries, and the same test scenarios and budgets.
