package com.example.retail.agent;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import com.example.retail.domain.EpisodicMemory;
import com.example.retail.domain.OrderHistory;
import com.example.retail.domain.ProductCatalog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * Retail assistant as a CoALA decision procedure on a LangGraph4j StateGraph.
 *
 * <pre>
 *  START -> retrieve -> propose -> evaluate -> select --execute--> execute -> reflect --continue--> retrieve (loop)
 *                                                |                              |
 *                                                |--clarify / abort--> answer   +--finish--> learn -> answer -> END
 *                                                +--finish-----------> learn
 * </pre>
 * The LLM proposes, evaluates, reflects and writes; plain code (select, tools, budgets) decides what actually happens.
 */
public class RetailAgent {
    static final String CLARIFY = "Could you tell me a bit more about what you are looking for (category, budget, how you will use it)?";

    private final LlmClient llm;
    private final VectorStore vectorStore;
    private final Tools tools;
    private final EpisodicMemory memory;
    private final AgentSettings settings;
    private final CompiledGraph<RetailState> graph;

    public RetailAgent(ChatModel model, VectorStore vectorStore, ProductCatalog catalog, OrderHistory orders,
                       EpisodicMemory memory, AgentSettings settings) {
        this.llm = new LlmClient(model);
        this.vectorStore = vectorStore;
        this.tools = new Tools(vectorStore, catalog, orders);
        this.memory = memory;
        this.settings = settings;
        this.graph = buildGraph();
    }

    public AgentResult ask(String customerId, String query) {
        Map<String, Object> input = new HashMap<>();
        input.put(RetailState.QUERY, query);
        input.put(RetailState.CUSTOMER, customerId);
        input.put(RetailState.ITERATION, 0);
        input.put(RetailState.FAILURES, 0);
        RetailState end = graph.invoke(input).orElseThrow(() -> new IllegalStateException("graph returned no state"));
        return new AgentResult(end.answer(), !end.clarification().isBlank(), end.iteration(), end.failures(),
                end.trace(), end.observations());
    }

    /** JSON repair retries so far (a retry that succeeds is not counted as a failure). */
    public int llmRetries() {
        return llm.retries();
    }

    // ------------------------------------------------------------------ graph

    private CompiledGraph<RetailState> buildGraph() {
        try {
            StateGraph<RetailState> g = new StateGraph<>(RetailState.SCHEMA, RetailState::new);
            g.addNode("retrieve", node_async(this::retrieve));
            g.addNode("propose", node_async(this::propose));
            g.addNode("evaluate", node_async(this::evaluate));
            g.addNode("select", node_async(this::select));
            g.addNode("execute", node_async(this::execute));
            g.addNode("reflect", node_async(this::reflect));
            g.addNode("learn", node_async(this::learn));
            g.addNode("answer", node_async(this::answer));

            g.addEdge(START, "retrieve");
            g.addEdge("retrieve", "propose");
            g.addEdge("propose", "evaluate");
            g.addEdge("evaluate", "select");
            g.addConditionalEdges("select", edge_async(RetailState::route),
                    Map.of("execute", "execute", "clarify", "answer", "finish", "learn", "abort", "answer"));
            g.addEdge("execute", "reflect");
            g.addConditionalEdges("reflect", edge_async(RetailState::route),
                    Map.of("continue", "retrieve", "finish", "learn"));
            g.addEdge("learn", "answer");
            g.addEdge("answer", END);
            return g.compile();
        } catch (GraphStateException e) {
            throw new IllegalStateException("invalid agent graph", e);
        }
    }

    // ------------------------------------------------------------------ nodes

    /** Retrieval action: episodic memory + semantic memory (vector store), re-run every cycle with the latest focus. */
    private Map<String, Object> retrieve(RetailState s) {
        List<String> notes = memory.recent(s.customer(), 3);
        String focus = s.summary().isBlank() ? s.query() : s.query() + " " + s.summary();
        String related = vectorStore.similaritySearch(SearchRequest.builder().query(focus).topK(2).build()).stream()
                .map(d -> String.valueOf(d.getMetadata().get("productId")))
                .collect(Collectors.joining(", "));
        String mem = (notes.isEmpty() ? "" : "Previously: " + String.join(" | ", notes) + "\n")
                + "Related products: " + related;
        return update(RetailState.MEMORY, mem, RetailState.TRACE, List.of("retrieve"));
    }

    /** Planning, step 1: the LLM proposes alternative next actions. Typed failure -> deterministic fallback. */
    private Map<String, Object> propose(RetailState s) {
        int failures = s.failures();
        String tag = "propose";
        List<Proposal> list;
        try {
            LlmDtos.ProposalsDto dto = llm.json("PROPOSE", Prompts.PROPOSE,
                    Prompts.user(s, settings.maxIterations(), null), LlmDtos.ProposalsDto.class);
            list = toProposals(dto);
        } catch (LlmFailure f) {
            failures++;
            tag = "propose:fallback";
            list = fallbackProposals(s);
        }
        return update(RetailState.PROPOSALS, list, RetailState.FAILURES, failures, RetailState.TRACE, List.of(tag));
    }

    /** Planning, step 2: score every proposal. Fallback = trust the proposer's own confidence. */
    private Map<String, Object> evaluate(RetailState s) {
        List<Proposal> props = s.proposals();
        int failures = s.failures();
        String tag = "evaluate";
        List<Proposal> scored = new ArrayList<>();
        try {
            LlmDtos.ScoresDto dto = llm.json("EVALUATE", Prompts.EVALUATE,
                    Prompts.user(s, settings.maxIterations(), props), LlmDtos.ScoresDto.class);
            if (dto.scores() == null || dto.scores().size() != props.size()) {
                throw new LlmFailure(LlmFailure.Kind.MALFORMED, "EVALUATE", null);
            }
            for (int i = 0; i < props.size(); i++) {
                Double v = dto.scores().get(i);
                scored.add(props.get(i).withScore(clamp(v == null ? 0.0 : v)));
            }
        } catch (LlmFailure f) {
            failures++;
            tag = "evaluate:fallback";
            scored.clear();
            for (Proposal p : props) scored.add(p.withScore(p.confidence()));
        }
        return update(RetailState.PROPOSALS, scored, RetailState.FAILURES, failures, RetailState.TRACE, List.of(tag));
    }

    /** Planning, step 3 (the governor): plain code picks the action, asks for clarification, or aborts. */
    private Map<String, Object> select(RetailState s) {
        List<Proposal> ps = s.proposals();
        if (s.failures() >= settings.maxFailures() || ps.isEmpty()) {
            return update(RetailState.ROUTE, "abort", RetailState.TRACE, List.of("select:abort"));
        }
        Proposal best = ps.stream().max(Comparator.comparingDouble(Proposal::score)).orElseThrow();
        boolean noEvidence = s.observations().isEmpty();

        if (best.score() < settings.clarifyThreshold() && s.iteration() == 0 && noEvidence) {
            return update(RetailState.ROUTE, "clarify", RetailState.SELECTED, best,
                    RetailState.CLARIFICATION, CLARIFY, RetailState.TRACE, List.of("select:clarify"));
        }
        if (best.tool().equals("finish") && noEvidence) { // the model may not end the loop before gathering any evidence
            best = new Proposal("search_catalog", s.query(), "no evidence yet", best.confidence(), best.score());
        }
        String route = best.tool().equals("finish") || s.iteration() >= settings.maxIterations() ? "finish" : "execute";
        return update(RetailState.ROUTE, route, RetailState.SELECTED, best, RetailState.TRACE, List.of("select:" + route));
    }

    /** Grounding action. */
    private Map<String, Object> execute(RetailState s) {
        Proposal p = s.selected().orElseThrow();
        String observation = tools.run(p, s.customer());
        return update(RetailState.OBSERVATIONS, List.of(observation), RetailState.TRACE, List.of("execute:" + p.tool()));
    }

    /** Reasoning action: interpret the new observation, decide whether to loop (interleaving retrieval + reasoning). */
    private Map<String, Object> reflect(RetailState s) {
        int failures = s.failures();
        String tag = "reflect";
        boolean met;
        String summary;
        try {
            LlmDtos.ReflectionDto dto = llm.json("REFLECT", Prompts.REFLECT,
                    Prompts.user(s, settings.maxIterations(), null), LlmDtos.ReflectionDto.class);
            met = Boolean.TRUE.equals(dto.goalSatisfied());
            summary = dto.summary() == null ? "" : dto.summary();
        } catch (LlmFailure f) {
            failures++;
            tag = "reflect:fallback";
            met = s.observations().stream().anyMatch(o -> o.startsWith("[check_stock]"));
            summary = "";
        }
        int next = s.iteration() + 1;
        boolean stop = met || next >= settings.maxIterations() || failures >= settings.maxFailures();
        return update(RetailState.ITERATION, next, RetailState.SUMMARY, summary, RetailState.FAILURES, failures,
                RetailState.ROUTE, stop ? "finish" : "continue", RetailState.TRACE, List.of(tag));
    }

    /** Learning action: write an episode to episodic memory (read-write); catalog and code stay read-only. */
    private Map<String, Object> learn(RetailState s) {
        String outcome = s.observations().stream().filter(o -> o.startsWith("[check_stock]"))
                .reduce((a, b) -> b).orElse("no verified recommendation");
        memory.remember(s.customer(), "asked '" + s.query() + "' -> " + outcome);
        return update(RetailState.TRACE, List.of("learn"));
    }

    private Map<String, Object> answer(RetailState s) {
        if (!s.clarification().isBlank()) {
            return update(RetailState.ANSWER, s.clarification(), RetailState.TRACE, List.of("answer:clarify"));
        }
        int failures = s.failures();
        String tag = "answer";
        String text;
        try {
            text = llm.text("ANSWER", Prompts.ANSWER, Prompts.user(s, settings.maxIterations(), null));
        } catch (LlmFailure f) {
            failures++;
            tag = "answer:fallback";
            text = fallbackAnswer(s);
        }
        return update(RetailState.ANSWER, text, RetailState.FAILURES, failures, RetailState.TRACE, List.of(tag));
    }

    // ------------------------------------------------------------------ helpers

    private static List<Proposal> toProposals(LlmDtos.ProposalsDto dto) {
        List<Proposal> out = new ArrayList<>();
        if (dto.proposals() != null) {
            for (LlmDtos.ProposalDto p : dto.proposals()) {
                if (p == null || p.tool() == null || !Tools.ALLOWED.contains(p.tool())) continue; // closed action space
                double conf = clamp(p.confidence() == null ? 0.5 : p.confidence());
                out.add(new Proposal(p.tool(), p.args() == null ? "" : p.args(),
                        p.rationale() == null ? "" : p.rationale(), conf, conf));
                if (out.size() == 3) break;
            }
        }
        if (out.isEmpty()) throw new LlmFailure(LlmFailure.Kind.MALFORMED, "PROPOSE", null);
        return out;
    }

    private static List<Proposal> fallbackProposals(RetailState s) {
        List<String> obs = s.observations();
        boolean searched = obs.stream().anyMatch(o -> o.startsWith("[search_catalog]"));
        boolean checked = obs.stream().anyMatch(o -> o.startsWith("[check_stock]"));
        String top = Tools.firstProductId(obs);
        if (!searched) return List.of(new Proposal("search_catalog", s.query(), "fallback: search the raw query", 0.5, 0.5));
        if (!checked && !top.isEmpty()) return List.of(new Proposal("check_stock", top, "fallback: verify top hit", 0.5, 0.5));
        return List.of(new Proposal("finish", "", "fallback: nothing more to do", 0.5, 0.5));
    }

    private static String fallbackAnswer(RetailState s) {
        Optional<String> last = s.observations().stream()
                .filter(o -> o.startsWith("[check_stock]") || o.startsWith("[search_catalog]"))
                .reduce((a, b) -> b);
        return last.map(o -> "Sorry, I could only partly complete this. Here is what I found: " + o)
                .orElse("Sorry, I could not complete your request right now. Please try again.");
    }

    private static double clamp(double d) {
        return Math.max(0.0, Math.min(1.0, d));
    }

    private static Map<String, Object> update(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
