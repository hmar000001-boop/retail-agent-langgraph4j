package com.example.retail.agent;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import com.example.retail.core.AdaptiveMemoryPolicy;
import com.example.retail.core.AgentLimits;
import com.example.retail.core.BoundedWorkspace;
import com.example.retail.core.CandidateEvaluator;
import com.example.retail.core.EvidenceVerifier;
import com.example.retail.core.IntentionGovernor;
import com.example.retail.core.IntentionRecord;
import com.example.retail.core.MemoryCandidate;
import com.example.retail.core.RecoverySubstate;
import com.example.retail.core.ResourceBudget;
import com.example.retail.core.TypedRecoveryPolicy;
import com.example.retail.core.UncertaintyEstimate;
import com.example.retail.core.WorkspaceCandidate;
import com.example.retail.domain.EpisodicMemory;
import com.example.retail.domain.OrderHistory;
import com.example.retail.domain.Product;
import com.example.retail.domain.ProductCatalog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * CoALA decision procedure on LangGraph4j. The graph owns execution control; the LLM only proposes and interprets.
 * Candidate scoring, goal verification, tool authority, budgets, intention changes and recovery stay in Java.
 */
public class RetailAgent {
    static final String CLARIFY = "Could you tell me a bit more about what you are looking for (category, budget, and intended use)?";
    private static final double ACCEPT_SCORE = 0.55;

    private final LlmClient llm;
    private final VectorStore vectorStore;
    private final Tools tools;
    private final EpisodicMemory memory;
    private final ProductCatalog catalog;
    private final AgentLimits limits;
    private final Map<String, AdaptiveMemoryPolicy> adaptiveMemoryByCustomer = new ConcurrentHashMap<>();
    private final BoundedWorkspace workspacePolicy = new BoundedWorkspace();
    private final Map<String, Double> workspaceAdmissionScores = new ConcurrentHashMap<>();
    private final IntentionGovernor intentionGovernor = new IntentionGovernor();
    private final TypedRecoveryPolicy recoveryPolicy = new TypedRecoveryPolicy();
    private final CompiledGraph<RetailState> graph;

    public RetailAgent(ChatModel model, VectorStore vectorStore, ProductCatalog catalog, OrderHistory orders,
                       EpisodicMemory memory, AgentSettings settings) {
        this.llm = new LlmClient(model);
        this.vectorStore = vectorStore;
        this.catalog = catalog;
        this.tools = new Tools(catalog, orders);
        this.memory = memory;
        this.limits = settings.limits();
        this.graph = buildGraph();
    }

    public AgentResult ask(String customerId, String query) {
        Map<String, Object> input = new HashMap<>();
        input.put(RetailState.QUERY, query);
        input.put(RetailState.CUSTOMER, customerId);
        input.put(RetailState.ITERATION, 0);
        input.put(RetailState.PLANNING_ROUND, 0);
        input.put(RetailState.FAILURES, 0);
        input.put(RetailState.INTENTION, IntentionRecord.start(query));
        input.put(RetailState.BUDGET, ResourceBudget.start(limits.maxPlanningRounds(),
                limits.maxToolCalls(), limits.maxModelCalls()));
        input.put(RetailState.UNCERTAINTY, UncertaintyEstimate.unknown("no proposal has been evaluated yet"));
        RetailState end = graph.invoke(input).orElseThrow(() -> new IllegalStateException("graph returned no state"));
        return new AgentResult(end.answer(), !end.clarification().isBlank(), end.iteration(), end.failures(),
                end.trace(), end.observations());
    }

    public int llmRetries() { return llm.retries(); }

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
            g.addNode("recover", node_async(this::recover));
            g.addNode("learn", node_async(this::learn));
            g.addNode("answer", node_async(this::answer));

            g.addEdge(START, "retrieve");
            g.addEdge("retrieve", "propose");
            g.addEdge("propose", "evaluate");
            g.addEdge("evaluate", "select");
            // Rejection returns to retrieval: planning can acquire new evidence and then propose again.
            g.addConditionalEdges("select", edge_async(RetailState::route),
                    Map.of("execute", "execute", "replan", "retrieve", "clarify", "answer",
                            "finish", "learn", "abort", "learn"));
            g.addEdge("execute", "reflect");
            g.addConditionalEdges("reflect", edge_async(RetailState::route),
                    Map.of("continue", "retrieve", "recover", "recover", "finish", "learn"));
            g.addConditionalEdges("recover", edge_async(RetailState::route),
                    Map.of("continue", "retrieve", "finish", "learn"));
            g.addEdge("learn", "answer");
            g.addEdge("answer", END);
            // LangGraph4j 1.8 applies this engine-level ceiling at compile time. The application
            // planning, tool and model budgets are stricter; this is a final infinite-loop guard.
            long applicationBound = 8L * (limits.maxIterations() + limits.maxPlanningRounds()) + 16L;
            int recursionLimit = (int) Math.max(64L, applicationBound);
            return g.compile(CompileConfig.builder().recursionLimit(recursionLimit).build());
        } catch (GraphStateException e) {
            throw new IllegalStateException("invalid agent graph", e);
        }
    }

    // ------------------------------------------------------------------ nodes

    /** B1 + B3: retrieve candidate content, select under a cost budget, then admit/broadcast a bounded workspace. */
    private Map<String, Object> retrieve(RetailState s) {
        AdaptiveMemoryPolicy adaptive = adaptiveMemoryByCustomer.computeIfAbsent(s.customer(), ignored -> new AdaptiveMemoryPolicy());
        List<String> notes = memory.recent(s.customer(), 5);
        for (String note : notes) {
            String id = "episode-" + Integer.toHexString((s.customer() + note).hashCode());
            adaptive.remember(new MemoryCandidate(id, "Previously: " + note, 0.65, 30, 0.55, 0.15, tokenCost(note)));
        }

        String focus = s.query() + (s.summary().isBlank() ? "" : " " + s.summary());
        List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder().query(focus).topK(4).build());
        for (Document d : docs) {
            String productId = String.valueOf(d.getMetadata().get("productId"));
            adaptive.remember(new MemoryCandidate("catalog-" + productId, d.getText(), 0.75, 20, 0.70, 0.10, tokenCost(d.getText())));
        }
        List<MemoryCandidate> memorySelection = adaptive.select(limits.memoryTokenBudget());

        List<WorkspaceCandidate> candidates = new ArrayList<>();
        candidates.add(withWorkspaceScore(new WorkspaceCandidate("intention", WorkspaceCandidate.CandidateType.ACTIVE_INTENTION,
                "Committed goal: " + s.intention().committedGoal() + " | method: " + s.intention().currentMethod(),
                1.0, 28, List.of("proposal", "evaluation", "selection", "execution", "monitor"))));
        for (MemoryCandidate item : memorySelection) {
            candidates.add(withWorkspaceScore(new WorkspaceCandidate("memory:" + item.id(), WorkspaceCandidate.CandidateType.MEMORY,
                    item.content(), memoryUtility(item), item.tokenCost(),
                    List.of("proposal", "evaluation", "selection"))));
        }
        if (!s.observations().isEmpty()) {
            String last = s.observations().getLast();
            candidates.add(withWorkspaceScore(new WorkspaceCandidate("latest-observation-" + Integer.toHexString(last.hashCode()),
                    WorkspaceCandidate.CandidateType.TOOL_RESULT, last, 0.98, tokenCost(last),
                    List.of("proposal", "evaluation", "selection", "monitor"))));
        }
        if (!s.feedback().isBlank()) {
            candidates.add(withWorkspaceScore(new WorkspaceCandidate("planning-feedback-" + Integer.toHexString(s.feedback().hashCode()),
                    WorkspaceCandidate.CandidateType.MONITOR, "Previous candidate set rejected: " + s.feedback(),
                    0.95, tokenCost(s.feedback()), List.of("proposal", "evaluation", "selection"))));
        }

        BoundedWorkspace.Admission admission = workspacePolicy.admit(candidates, limits.workspaceTokenBudget());
        String workspaceText = admission.admitted().stream().map(WorkspaceCandidate::content).collect(Collectors.joining("\n"));
        String mem = workspaceText.isBlank() ? "(empty bounded workspace)" : workspaceText;
        return update(RetailState.MEMORY, mem,
                RetailState.WORKSPACE, admission.admitted(),
                RetailState.TRACE, List.of("retrieve", "workspace:admit=" + admission.admitted().size()
                        + "/reject=" + admission.rejectedIds().size() + "/winner=" + admission.winnerId()));
    }

    /** Proposal: model text is a candidate only; it does not gain action authority. */
    private Map<String, Object> propose(RetailState s) {
        int failures = s.failures();
        ResourceBudget budget = s.budget();
        String tag = "propose";
        List<Proposal> list;
        try {
            if (!budget.canCallModel()) throw new LlmFailure(LlmFailure.Kind.PROVIDER, "PROPOSE", null);
            budget = budget.modelUsed();
            LlmDtos.ProposalsDto dto = llm.json("PROPOSE", Prompts.PROPOSE,
                    Prompts.user(s, limits.maxIterations(), null), LlmDtos.ProposalsDto.class);
            list = toProposals(dto);
        } catch (LlmFailure f) {
            failures++;
            tag = "propose:fallback";
            list = fallbackProposals(s);
        }
        return update(RetailState.PROPOSALS, list, RetailState.FAILURES, failures,
                RetailState.BUDGET, budget, RetailState.TRACE, List.of(tag));
    }

    /** Independent evaluator. Never reads model confidence when assigning a decision score. */
    private Map<String, Object> evaluate(RetailState s) {
        List<Proposal> scored = CandidateEvaluator.evaluateAll(s.proposals(), s.query(), s.observations(), catalog);
        double selfConfidence = scored.stream().max(Comparator.comparingDouble(Proposal::score))
                .map(Proposal::confidence).orElse(0.0);
        return update(RetailState.PROPOSALS, scored,
                RetailState.UNCERTAINTY, UncertaintyEstimate.uncalibratedModelEstimate(selfConfidence),
                RetailState.TRACE, List.of("evaluate", "evaluate:independent-evidence-policy"));
    }

    /** CoALA selection + B4/B6 governor: accept a feasible action, reject all, or terminate safely. */
    private Map<String, Object> select(RetailState s) {
        List<Proposal> proposals = s.proposals();
        ResourceBudget budget = s.budget();
        if (s.failures() >= limits.maxFailures() || proposals.isEmpty()) {
            return update(RetailState.ROUTE, "abort", RetailState.TRACE, List.of("select:abort"));
        }

        Proposal best = proposals.stream().max(Comparator.comparingDouble(Proposal::score)).orElseThrow();
        boolean verified = EvidenceVerifier.goalSatisfied(s.query(), s.observations(), catalog);
        if ("finish".equals(best.tool())) {
            if (verified) {
                IntentionRecord completed = s.intention().terminate("COMPLETED");
                return update(RetailState.ROUTE, "finish", RetailState.SELECTED, best,
                        RetailState.GOAL_SATISFIED, true, RetailState.INTENTION, completed,
                        RetailState.TRACE, List.of("select:finish-authorized-by-evidence"));
            }
            return rejectAndReplan(s, "finish rejected: no matching, current, in-stock product evidence is available");
        }

        if (best.score() < ACCEPT_SCORE) {
            return rejectAndReplan(s, "all candidates rejected by independent evaluator; retrieve new evidence or revise candidates");
        }
        if (!budget.canUseTool()) {
            IntentionRecord suspended = s.intention().terminate("SUSPENDED_BUDGET");
            return update(RetailState.ROUTE, "abort", RetailState.INTENTION, suspended,
                    RetailState.TRACE, List.of("select:tool-budget-exhausted"));
        }
        return update(RetailState.ROUTE, "execute", RetailState.SELECTED, best,
                RetailState.TRACE, List.of("select:execute", "select:tool=" + best.tool(), "select:score=" + best.score()));
    }

    private Map<String, Object> rejectAndReplan(RetailState s, String feedback) {
        ResourceBudget budget = s.budget();
        if (budget.canPlan() && s.planningRound() < limits.maxPlanningRounds()) {
            return update(RetailState.ROUTE, "replan",
                    RetailState.PLANNING_ROUND, s.planningRound() + 1,
                    RetailState.BUDGET, budget.planned(),
                    RetailState.FEEDBACK, feedback,
                    RetailState.TRACE, List.of("select:reject-all:replan=" + (s.planningRound() + 1)));
        }
        if (s.observations().isEmpty()) {
            return update(RetailState.ROUTE, "clarify", RetailState.CLARIFICATION, CLARIFY,
                    RetailState.TRACE, List.of("select:clarify-after-rejected-planning"));
        }
        return update(RetailState.ROUTE, "abort", RetailState.TRACE, List.of("select:stop-after-planning-budget"));
    }

    /** Grounding action. Closed tool whitelist remains the only route to side effects. */
    private Map<String, Object> execute(RetailState s) {
        Proposal proposal = s.selected().orElseThrow();
        String observation = tools.run(proposal, s.customer());
        ResourceBudget budget = s.budget().toolUsed();
        int failures = s.failures() + (observation.startsWith("[error]") || observation.contains("unknown product id") ? 1 : 0);
        return update(RetailState.OBSERVATIONS, List.of(observation), RetailState.BUDGET, budget,
                RetailState.FAILURES, failures, RetailState.TRACE, List.of("execute:" + proposal.tool()));
    }

    /**
     * Reflection can summarize, but its Boolean goalSatisfied claim is non-authoritative.
     * The Java evidence verifier controls completion; B6 state and budgets propagate across the cycle.
     */
    private Map<String, Object> reflect(RetailState s) {
        int failures = s.failures();
        ResourceBudget budget = s.budget();
        String tag = "reflect";
        String summary = "";
        try {
            if (!budget.canCallModel()) throw new LlmFailure(LlmFailure.Kind.PROVIDER, "REFLECT", null);
            budget = budget.modelUsed();
            LlmDtos.ReflectionDto dto = llm.json("REFLECT", Prompts.REFLECT,
                    Prompts.user(s, limits.maxIterations(), null), LlmDtos.ReflectionDto.class);
            summary = dto.summary() == null ? "" : dto.summary();
        } catch (LlmFailure f) {
            failures++;
            tag = "reflect:fallback";
        }

        boolean verified = EvidenceVerifier.goalSatisfied(s.query(), s.observations(), catalog);
        String last = s.observations().isEmpty() ? "" : s.observations().getLast();
        boolean toolFailure = last.startsWith("[error]") || last.contains("unknown product id");
        int nextIteration = s.iteration() + 1;
        boolean remaining = budget.canPlan() && budget.canUseTool() && nextIteration < limits.maxIterations();
        IntentionGovernor.Result governed = intentionGovernor.afterEvidence(s.intention(), verified, toolFailure, remaining);
        String route;
        RecoverySubstate recovery = s.recovery().orElse(null);
        if (toolFailure) route = "recover";
        else if (verified || nextIteration >= limits.maxIterations() || failures >= limits.maxFailures()) route = "finish";
        else route = "continue";

        List<String> verifiedIds = EvidenceVerifier.verifiedProducts(s.query(), s.observations(), catalog).stream()
                .map(Product::id).toList();
        return update(RetailState.ITERATION, nextIteration,
                RetailState.SUMMARY, summary,
                RetailState.FAILURES, failures,
                RetailState.BUDGET, budget,
                RetailState.GOAL_SATISFIED, verified,
                RetailState.VERIFIED_PRODUCT_IDS, verifiedIds,
                RetailState.INTENTION, governed.intention(),
                RetailState.ROUTE, route,
                RetailState.TRACE, List.of(tag, "reflect:evidence-verified=" + verified,
                        "governor:" + governed.decision().name().toLowerCase()));
    }

    /** B2: typed child recovery with a protected parent goal and bounded attempts. */
    private Map<String, Object> recover(RetailState s) {
        String last = s.observations().isEmpty() ? "" : s.observations().getLast();
        RecoverySubstate.FailureType type = last.contains("unknown product")
                ? RecoverySubstate.FailureType.ITEM_NOT_FOUND
                : last.contains("contract") ? RecoverySubstate.FailureType.TOOL_CONTRACT
                : last.contains("conflict") ? RecoverySubstate.FailureType.EVIDENCE_CONFLICT
                : RecoverySubstate.FailureType.UNKNOWN;
        RecoverySubstate previous = s.recovery().orElse(null);
        RecoverySubstate child;
        if (previous != null && previous.failureType() == type && "RESOLVED".equals(previous.status())) {
            child = previous.nextAttempt();
        } else {
            child = RecoverySubstate.open(type, Integer.toHexString((s.query() + s.observations()).hashCode()),
                    s.intention().committedGoal(), limits.maxRecoveryAttempts());
        }
        TypedRecoveryPolicy.Resolution resolution = recoveryPolicy.resolve(child);
        boolean exhausted = type == RecoverySubstate.FailureType.UNKNOWN || !child.canRetry();
        RecoverySubstate resolved = child.resolved(resolution.type() + ": " + resolution.instruction());
        IntentionRecord updatedIntent = s.intention().changeMethod("recovery/" + resolution.type());
        if (exhausted) updatedIntent = updatedIntent.terminate("FAILED_RECOVERY");
        return update(RetailState.RECOVERY, resolved,
                RetailState.INTENTION, updatedIntent,
                RetailState.FEEDBACK, resolution.instruction(),
                RetailState.ROUTE, exhausted ? "finish" : "continue",
                RetailState.TRACE, List.of("recover:typed=" + type.name().toLowerCase(),
                        "recover:attempt=" + child.attempt(), "recover:parent-goal-protected",
                        "recover:compilation-eligible=" + resolution.eligibleForCompilation(),
                        "recover:status=" + (exhausted ? "unrecoverable" : "resolved")));
    }

    /** Learning is based on verified outcomes; failed or unverified attempts are not recorded as success. */
    private Map<String, Object> learn(RetailState s) {
        List<Product> verified = EvidenceVerifier.verifiedProducts(s.query(), s.observations(), catalog);
        String outcome = verified.isEmpty()
                ? "not verified: " + s.query()
                : "verified in-stock: " + verified.stream().map(p -> p.id() + " $" + p.price()).collect(Collectors.joining(", "));
        memory.remember(s.customer(), "asked '" + s.query() + "' -> " + outcome);

        AdaptiveMemoryPolicy adaptive = adaptiveMemoryByCustomer.get(s.customer());
        if (adaptive != null) {
            for (WorkspaceCandidate item : s.workspace()) {
                if (item.id().startsWith("memory:")) {
                    String id = item.id().substring("memory:".length());
                    adaptive.recordDownstreamUse(id, !verified.isEmpty());
                }
            }
        }
        int workspaceUpdates = 0;
        for (WorkspaceCandidate item : s.workspace()) {
            WorkspaceCandidate updated = workspacePolicy.updateAfterOutcome(item, !verified.isEmpty());
            workspaceAdmissionScores.put(item.id(), updated.admissionScore());
            workspaceUpdates++;
        }
        return update(RetailState.TRACE, List.of("learn", "learn:outcome=" + (verified.isEmpty() ? "unverified" : "verified"),
                "learn:workspace-admission-updated=" + workspaceUpdates));
    }

    /** Final prose is accepted only if it cites every verified product id and price and no unknown product id. */
    private Map<String, Object> answer(RetailState s) {
        if (!s.clarification().isBlank()) {
            return update(RetailState.ANSWER, s.clarification(), RetailState.TRACE, List.of("answer:clarify"));
        }
        int failures = s.failures();
        ResourceBudget budget = s.budget();
        String tag = "answer";
        String text;
        try {
            if (!budget.canCallModel()) throw new LlmFailure(LlmFailure.Kind.PROVIDER, "ANSWER", null);
            budget = budget.modelUsed();
            String candidate = llm.text("ANSWER", Prompts.ANSWER, Prompts.user(s, limits.maxIterations(), null));
            text = groundedAnswer(candidate, s) ? candidate : fallbackAnswer(s);
            if (!text.equals(candidate)) tag = "answer:grounding-fallback";
        } catch (LlmFailure f) {
            failures++;
            tag = "answer:fallback";
            text = fallbackAnswer(s);
        }
        return update(RetailState.ANSWER, text, RetailState.FAILURES, failures,
                RetailState.BUDGET, budget, RetailState.TRACE, List.of(tag));
    }

    // ------------------------------------------------------------------ helpers

    private boolean groundedAnswer(String candidate, RetailState state) {
        List<Product> verified = EvidenceVerifier.verifiedProducts(state.query(), state.observations(), catalog);
        if (verified.isEmpty()) return false;
        for (Product product : verified) {
            if (!candidate.contains(product.id()) || !candidate.contains(String.valueOf(product.price()))) return false;
        }
        java.util.regex.Matcher ids = java.util.regex.Pattern.compile("p-\\d+").matcher(candidate);
        while (ids.find()) {
            if (verified.stream().noneMatch(p -> p.id().equals(ids.group()))) return false;
        }
        return true;
    }

    private List<Proposal> toProposals(LlmDtos.ProposalsDto dto) {
        List<Proposal> out = new ArrayList<>();
        if (dto.proposals() != null) {
            for (LlmDtos.ProposalDto p : dto.proposals()) {
                if (p == null || p.tool() == null || !Tools.ALLOWED.contains(p.tool())) continue;
                double conf = clamp(p.confidence() == null ? 0.5 : p.confidence());
                out.add(new Proposal(p.tool(), p.args() == null ? "" : p.args(),
                        p.rationale() == null ? "" : p.rationale(), conf, 0.0));
                if (out.size() == 5) break;
            }
        }
        if (out.isEmpty()) throw new LlmFailure(LlmFailure.Kind.MALFORMED, "PROPOSE", null);
        return List.copyOf(out);
    }

    private List<Proposal> fallbackProposals(RetailState s) {
        List<String> obs = s.observations();
        boolean searched = obs.stream().anyMatch(o -> o.startsWith("[search_catalog]"));
        boolean checked = obs.stream().anyMatch(o -> o.startsWith("[check_stock]"));
        String top = Tools.firstProductId(obs);
        if (!searched) return List.of(new Proposal("search_catalog", s.query(), "fallback: search the current query", 0.5, 0.0));
        if (!checked && !top.isEmpty()) return List.of(new Proposal("check_stock", top, "fallback: verify a retrieved candidate", 0.5, 0.0));
        if (!EvidenceVerifier.goalSatisfied(s.query(), obs, catalog)) {
            return List.of(new Proposal("search_catalog", s.query() + " alternatives", "fallback: retrieve alternatives after evidence mismatch", 0.5, 0.0));
        }
        return List.of(new Proposal("finish", "", "fallback: evidence verifier reports goal satisfied", 0.5, 0.0));
    }

    private String fallbackAnswer(RetailState s) {
        List<Product> verified = EvidenceVerifier.verifiedProducts(s.query(), s.observations(), catalog);
        if (!verified.isEmpty()) {
            return "Based on current catalogue verification, I recommend: " + verified.stream()
                    .map(p -> p.name() + " (" + p.id() + ") at $" + p.price() + "; " + p.stock() + " units shown in stock")
                    .collect(Collectors.joining(". ")) + ".";
        }
        Optional<String> lastSearch = s.observations().stream().filter(o -> o.startsWith("[search_catalog]"))
                .reduce((a, b) -> b);
        return lastSearch.map(o -> "I found possible catalogue matches, but could not verify a current in-stock item satisfying all constraints. Search evidence: " + o)
                .orElse("I could not verify a matching product within the available planning and tool budget. " + CLARIFY);
    }

    private WorkspaceCandidate withWorkspaceScore(WorkspaceCandidate candidate) {
        double score = workspaceAdmissionScores.getOrDefault(candidate.id(), candidate.admissionScore());
        return new WorkspaceCandidate(candidate.id(), candidate.type(), candidate.content(), score,
                candidate.tokenCost(), candidate.broadcastRecipients());
    }

    private static int tokenCost(String value) { return Math.max(8, (value == null ? 0 : value.length()) / 4); }
    private static double memoryUtility(MemoryCandidate item) {
        return (item.activation() * item.downstreamValue()) /
                (1.0 + item.predictedRetrievalLatencyMs() / 100.0 + item.maintenanceCost());
    }
    private static double clamp(double v) { return Math.max(0.0, Math.min(1.0, v)); }

    private static Map<String, Object> update(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
