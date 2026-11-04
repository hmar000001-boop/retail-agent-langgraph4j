# Retail Agent — CoALA / Fan & Lan mechanism study

A single Maven reactor containing two independent implementations of the same retail-agent example:

- **`retail-agent-langgraph4j`** — the supplied LangGraph4j + Spring AI implementation, refactored to use the shared Java core.
- **`retail-agent-langchain4j`** — a separate LangChain4j Agentic implementation using a custom `Planner` and the same shared Java policies.
- **`retail-agent-core`** — framework-neutral domain objects and control policies shared by both implementations.

The purpose is to study the *same application semantics* through two orchestration approaches, not to claim that either framework implements CoALA or Fan & Lan automatically.

## Requirements

- JDK 21
- Maven 3.9+
- Network access on the first build to resolve Maven dependencies

The original upload did not include Maven Wrapper files. Maven was not available in the packaging environment, and outbound Maven repository access was unavailable there, so this source bundle has **not** been verified by a full Maven reactor build in this environment. Run the build commands below on a machine with Maven and dependency access before treating it as build-verified.

## Build and tests

From this directory:

```bash
mvn -B -ntp clean verify
```

Run one module and its shared dependency:

```bash
mvn -B -ntp -pl retail-agent-langchain4j -am test
mvn -B -ntp -pl retail-agent-langgraph4j -am test
```

Run the LangChain4j Agentic demo with its deterministic simulator:

```bash
mvn -pl retail-agent-langchain4j -am exec:java \
  -Dexec.mainClass=com.example.retail.langchain.RetailAgenticApplication
```

The module pins the Maven Exec plugin in its build so the main class is launched with the reactor dependency classpath.

Run the LangChain4j implementation against OpenAI:

```bash
OPENAI_API_KEY=... RETAIL_LLM_PROVIDER=openai \
  mvn -pl retail-agent-langchain4j -am exec:java \
  -Dexec.mainClass=com.example.retail.langchain.RetailAgenticApplication
```

`OPENAI_MODEL` optionally overrides the default model name (`gpt-4o-mini`). The simulated provider is the default and needs no key.

The LangGraph4j module remains a Spring Boot application. Its deterministic provider is selected by default in `application.yml`; tests exercise the graph directly. Start its application context with:

```bash
mvn -pl retail-agent-langgraph4j -am spring-boot:run
```

For live OpenAI calls, set `APP_LLM_PROVIDER=real` and `OPENAI_API_KEY`. The graph implementation's existing configuration also supports local scripted, cassette replay, recording, and fault injection; see [`retail-agent-langgraph4j/README.md`](retail-agent-langgraph4j/README.md).

## Example task

> Find waterproof trail-running shoes under $150 and recommend only an item whose current stock is verified.

The catalogue includes:

- `p-103`, Peak Trail GTX — waterproof trail-running shoe, $149.00, 4 units in stock.
- `p-101`, Trail Runner X — trail-running shoe, $89.00, 12 units in stock, but its catalogue description does not establish waterproofing.

A search result alone is not completion evidence. The Java verifier requires a matching product, current price/stock observation, positive stock, and compliance with the parsed request constraints.

## Control flow

Both implementations separate the model's **proposal/interpretation authority** from runtime **transition authority**.

1. Retrieve long-term-memory and catalogue candidates.
2. Compete for bounded workspace admission; record broadcast recipients.
3. Ask the LLM for candidate actions (proposal only).
4. Independently evaluate the proposals using deterministic Java rules.
5. Select one authorized action, reject all candidates and replan, or terminate safely.
6. Execute only a whitelisted grounding action.
7. Incorporate the observation; an LLM reflection is advisory, while Java verifies completion.
8. Continue, recover in a bounded typed substate, learn from the outcome, or answer.

The **inner planning loop** is distinct from the **outer decision cycle**. Rejection can return to retrieval/proposal before executing an action. After an action, the observation can start another decision cycle.

## What is shared and what is intentionally different

Shared:

- Retail domain and deterministic catalogue source of truth.
- Proposal record, independent evaluator, evidence verifier.
- Intention/governor model, uncertainty metadata, resource limits.
- Adaptive memory and bounded workspace policies.
- Typed recovery record and policy.

Resource ceilings are aligned through `AgentLimits` defaults: 3 outer action cycles, 3 planning rejections, 4 failures, 3 tool calls, 8 model calls, 2 recovery attempts, 180 memory tokens and a 260-token workspace. LangGraph4j also has a compile-time recursion ceiling derived from its application limits as a final guard.

Different:

- LangGraph4j owns graph state and node/edge transitions. `Proposal` and the core state records are serializable because LangGraph4j snapshots state between nodes.
- LangChain4j Agentic owns the custom `Planner` and `AgenticScope`. Only JSON-friendly JDK values are written into scope; the planner cursor is explicit and `executionState()` / `restoreExecutionState()` support planner-state restoration.
- The LangGraph4j retrieval node uses Spring AI's vector store and local hash embeddings by default; the LangChain4j retrieval adapter uses deterministic lexical catalogue search. The explicit `search_catalog` grounding action uses the shared deterministic lexical catalogue search in both. **Initial memory retrieval is therefore not yet a controlled variable in a rigorous framework benchmark.** Use the same retriever adapter in both before drawing performance conclusions.
- LangGraph4j includes scripted, record/replay, and fault-injection model infrastructure. LangChain4j currently has a scripted `ChatModel` for deterministic control-flow tests; matching cassette and fault-injection parity is a follow-up task.

## Paper alignment and scope

Read [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and [`docs/FAN_LAN_MECHANISM_MATRIX.md`](docs/FAN_LAN_MECHANISM_MATRIX.md) before interpreting the names in code.

- CoALA is the architecture-level framework: working and long-term memory, internal/external actions, and a repeated decision procedure with planning and execution.
- Fan & Lan provide a mechanism-level review and propose residual control-coupling hypotheses B1, B2, B3, B4 and B6. They do **not** prescribe implementing every bundle in every agent.
- B5 is treated as closed within the paper's reviewed corpus through GraSP; it is a closest-baseline / comparison point, not a sixth unresolved bundle.
- The runtime contains **partial, illustrative mechanisms**, not complete faithful reconstructions of ACT-R, Soar, LIDA, BDI, MIDCA, EPIC or Sigma.
- The heuristic scoring functions in this repository are engineering choices for an experiment. They are not equations or algorithms specified by the papers.
- No mechanism is accepted merely because a field exists. Tests and traces must show the trigger, control effect, state transition, persistence and outcome update.

## Persistence and production boundaries

This is an executable teaching example, not a production deployment:

- Both modules currently keep per-run state and learning-policy maps in process memory.
- No durable LangGraph4j checkpoint store is configured.
- No LangChain4j `AgenticScopeStore` is configured. The custom planner exposes execution state for restoration, but that alone is not durable persistence.
- B2 recovery preserves the committed goal and records a typed child/recovery resolution; it does **not** yet compile a resolution into reusable production rules. `eligibleForCompilation` remains false.
- B6 uncertainty is explicitly marked uncalibrated. Model self-confidence is recorded as provenance only and never treated as calibrated probability.
- The retail catalogue is an in-memory fixture; `check_stock` reports that fixture's state, not a live retailer inventory service.
- Human approval, authentication, multi-tenant authorization, external side-effect idempotency and durable audit storage are not implemented.

## Dependency pins

Versions are pinned in the root `pom.xml` properties and module POMs:

- Java 21
- Spring Boot parent 4.1.0
- Spring AI 2.0.1
- LangGraph4j 1.8.25
- LangChain4j 1.23.0

The LangChain4j `agentic` module is documented by its maintainers as **experimental** and subject to change. Keep it isolated from the framework-neutral core and revalidate its planner/persistence APIs when upgrading.

## References

- CoALA, Sumers et al., *Cognitive Architectures for Language Agents*: <https://arxiv.org/abs/2309.02427>
- Fan & Lan, *From Cognitive Architectures to Language Agents: A Mechanism-Level Review of Lineage, Convergence, and Migration Gaps*: <https://arxiv.org/abs/2607.23942>
- LangChain4j Agentic documentation: <https://docs.langchain4j.dev/tutorials/agents/>
- Previous code-level review: [`docs/reports/Retail_Agent_CoALA_Fan_Lan_Implementation_Audit.docx`](docs/reports/Retail_Agent_CoALA_Fan_Lan_Implementation_Audit.docx)
