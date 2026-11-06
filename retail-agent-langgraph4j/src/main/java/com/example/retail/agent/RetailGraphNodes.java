package com.example.retail.agent;

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
import com.example.retail.agent.domain.ActionDefinition;
import com.example.retail.agent.domain.ActionInvocation;
import com.example.retail.agent.domain.ActionOutcome;
import com.example.retail.agent.domain.PendingActionApproval;
import com.example.retail.agent.port.ActionRegistry;
import com.example.retail.agent.port.ActionGateway;
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
import java.util.concurrent.CompletableFuture;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * Application node operations for the primary LangGraph4j runtime.
 * Graph topology and checkpoint lifecycle are deliberately owned by RetailAgentGraph.
 */
public final class RetailGraphNodes {
    private static final double ACCEPT_SCORE = 0.55;

    private final LlmClient llm;
    private final VectorStore vectorStore;
    private final ActionRegistry actionRegistry;
    private final ActionGateway actionGateway;
    private final EpisodicMemory memory;
    private final ProductCatalog catalog;
    private final AgentLimits limits;
    private final Map<String, AdaptiveMemoryPolicy> adaptiveMemoryByCustomer = new ConcurrentHashMap<>();
    private final BoundedWorkspace workspacePolicy = new BoundedWorkspace();
    private final Map<String, Double> workspaceAdmissionScores = new ConcurrentHashMap<>();
    private final IntentionGovernor intentionGovernor = new IntentionGovernor();
    private final TypedRecoveryPolicy recoveryPolicy = new TypedRecoveryPolicy();
    public RetailGraphNodes(LlmClient llm, VectorStore vectorStore, ProductCatalog catalog, OrderHistory orders,
                            EpisodicMemory memory, AgentSettings settings) {
        this(llm, vectorStore, catalog, orders, memory, settings,
                new RetailActionRegistry(), null);
    }

    /** Injectable action boundary for policy tests and future real infrastructure adapters. */
    public RetailGraphNodes(LlmClient llm, VectorStore vectorStore, ProductCatalog catalog, OrderHistory orders,
                            EpisodicMemory memory, AgentSettings settings, ActionRegistry actionRegistry,
                            ActionGateway actionGateway) {
        this.llm = java.util.Objects.requireNonNull(llm, "llm");
        this.vectorStore = java.util.Objects.requireNonNull(vectorStore, "vectorStore");
        this.catalog = java.util.Objects.requireNonNull(catalog, "catalog");
        this.actionRegistry = java.util.Objects.requireNonNull(actionRegistry, "actionRegistry");
        this.actionGateway = actionGateway == null
                ? new RetailActionGateway(actionRegistry, catalog, java.util.Objects.requireNonNull(orders, "orders"))
                : actionGateway;
        this.memory = java.util.Objects.requireNonNull(memory, "memory");
        this.limits = java.util.Objects.requireNonNull(settings, "settings").limits();
    }

    // ------------------------------------------------------------------ nodes

    /** Start a retrieval fan-out; the two following graph branches execute independently. */
    Map<String, Object> beginRetrieval(RetailState s) {
        return update(RetailState.TRACE, List.of("retrieve:" + phase(s), "retrieve:parallel-fanout:" + phase(s)));
    }

    /** Episodic retrieval branch. The list is merged through the shared appender channel. */
    Map<String, Object> retrieveEpisodic(RetailState s) {
        List<MemoryCandidate> candidates = new ArrayList<>();
        for (String note : memory.recent(s.customer(), 5)) {
            String id = "episode-" + Integer.toHexString((s.customer() + note).hashCode());
            candidates.add(new MemoryCandidate(id, "Previously: " + note, 0.65, 30, 0.55, 0.15, tokenCost(note)));
        }
        return update(RetailState.EPISODIC_CANDIDATES, List.copyOf(candidates));
    }

    /** Semantic/catalogue retrieval branch. Retrieved product facts remain non-authoritative until checked by tools. */
    Map<String, Object> retrieveCatalog(RetailState s) {
        String focus = s.query() + (s.summary().isBlank() ? "" : " " + s.summary());
        List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder().query(focus).topK(4).build());
        List<MemoryCandidate> candidates = new ArrayList<>();
        for (Document d : docs) {
            Object productId = d.getMetadata().get("productId");
            if (productId == null) continue;
            candidates.add(new MemoryCandidate("catalog-" + productId, d.getText(), 0.75, 20,
                    0.70, 0.10, tokenCost(d.getText())));
        }
        return update(RetailState.CATALOG_CANDIDATES, List.copyOf(candidates));
    }

    /** Merge the parallel retrieval results, apply adaptive memory selection, then admit a strict workspace. */
    Map<String, Object> admitWorkspace(RetailState s) {
        AdaptiveMemoryPolicy adaptive = adaptiveMemoryByCustomer.computeIfAbsent(s.customer(), ignored -> new AdaptiveMemoryPolicy());
        Map<String, MemoryCandidate> deduplicated = new java.util.LinkedHashMap<>();
        for (MemoryCandidate candidate : s.retrievalCandidates()) {
            adaptive.remember(candidate);
            deduplicated.put(candidate.id(), candidate);
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
        String phase = phase(s);
        return update(RetailState.MEMORY, mem,
                RetailState.WORKSPACE, admission.admitted(),
                RetailState.TRACE, List.of("workspace:admit=" + admission.admitted().size()
                        + "/reject=" + admission.rejectedIds().size() + "/winner=" + admission.winnerId() + ":" + phase));
    }

    /** Compatibility wrapper for direct tests; the compiled graph uses the non-blocking async method. */
    Map<String, Object> propose(RetailState s) {
        return proposeAsync(s).join();
    }

    /** Proposal: model text is a candidate only; it does not gain action authority. */
    CompletableFuture<Map<String, Object>> proposeAsync(RetailState s) {
        int failures = s.failures();
        ResourceBudget budget = s.budget();
        if (!budget.canCallModel()) {
            return CompletableFuture.completedFuture(update(RetailState.PROPOSALS, fallbackProposals(s),
                    RetailState.FAILURES, failures + 1, RetailState.TRACE,
                    List.of("propose:fallback:" + phase(s))));
        }
        return llm.jsonTrackedAsync("PROPOSE", Prompts.PROPOSE,
                Prompts.user(s, limits.maxIterations(), null), LlmDtos.ProposalsDto.class,
                budget.modelCallsRemaining()).thenApply(result -> {
            ResourceBudget updatedBudget = budget.modelUsed(result.callsUsed());
            List<Proposal> proposals;
            int updatedFailures = failures;
            String tag = "propose";
            try {
                if (!result.succeeded()) throw result.failure();
                proposals = toProposals(result.value());
            } catch (RuntimeException failure) {
                updatedFailures++;
                tag = "propose:fallback";
                proposals = fallbackProposals(s);
            }
            return update(RetailState.PROPOSALS, proposals, RetailState.FAILURES, updatedFailures,
                    RetailState.BUDGET, updatedBudget, RetailState.TRACE, List.of(tag + ":" + phase(s)));
        });
    }

    /** Independent evaluator. Never reads model confidence when assigning a decision score. */
    Map<String, Object> evaluate(RetailState s) {
        List<Proposal> scored = CandidateEvaluator.evaluateAll(s.proposals(), s.query(), s.observations(), catalog);
        double selfConfidence = scored.stream().max(Comparator.comparingDouble(Proposal::score))
                .map(Proposal::confidence).orElse(0.0);
        String phase = phase(s);
        return update(RetailState.PROPOSALS, scored,
                RetailState.UNCERTAINTY, UncertaintyEstimate.uncalibratedModelEstimate(selfConfidence),
                RetailState.TRACE, List.of("evaluate:" + phase,
                        "evaluate:independent-evidence-policy:" + phase));
    }

    /** CoALA selection + B4/B6 governor: accept a feasible action, reject all, or terminate safely. */
    Map<String, Object> select(RetailState s) {
        List<Proposal> proposals = s.proposals();
        ResourceBudget budget = s.budget();
        if (s.failures() >= limits.maxFailures() || proposals.isEmpty()) {
            return update(RetailState.ROUTE, "abort", RetailState.TRACE, List.of("select:abort:" + phase(s)));
        }

        Proposal best = proposals.stream().max(Comparator.comparingDouble(Proposal::score)).orElseThrow();
        boolean verified = EvidenceVerifier.goalSatisfied(s.query(), s.observations(), catalog);
        if ("finish".equals(best.tool())) {
            if (verified) {
                IntentionRecord completed = s.intention().terminate("COMPLETED");
                return update(RetailState.ROUTE, "finish", RetailState.SELECTED, best,
                        RetailState.GOAL_SATISFIED, true, RetailState.INTENTION, completed,
                        RetailState.TRACE, List.of("select:finish-authorized-by-evidence:" + phase(s)));
            }
            return rejectAndReplan(s, "finish rejected: no matching, current, in-stock product evidence is available");
        }

        if (best.score() < ACCEPT_SCORE) {
            return rejectAndReplan(s, "all candidates rejected by independent evaluator; retrieve new evidence or revise candidates");
        }
        if (!budget.canUseTool()) {
            IntentionRecord suspended = s.intention().terminate("SUSPENDED_BUDGET");
            return update(RetailState.ROUTE, "abort", RetailState.INTENTION, suspended,
                    RetailState.TRACE, List.of("select:tool-budget-exhausted:" + phase(s)));
        }
        String phase = phase(s);
        return update(RetailState.ROUTE, "authorize", RetailState.SELECTED, best,
                RetailState.TRACE, List.of("select:execute:" + phase,
                        "select:tool=" + best.tool() + ":" + phase,
                        "select:score=" + best.score() + ":" + phase));
    }

    Map<String, Object> rejectAndReplan(RetailState s, String feedback) {
        ResourceBudget budget = s.budget();
        if (budget.canPlan() && s.planningRound() < limits.maxPlanningRounds()) {
            return update(RetailState.ROUTE, "replan",
                    RetailState.PLANNING_ROUND, s.planningRound() + 1,
                    RetailState.BUDGET, budget.planned(),
                    RetailState.FEEDBACK, feedback,
                    RetailState.TRACE, List.of("select:reject-all:replan=" + (s.planningRound() + 1)
                            + ":" + phase(s)));
        }
        if (s.observations().isEmpty()) {
            return update(RetailState.ROUTE, "clarify", RetailState.CLARIFICATION, CLARIFY,
                    RetailState.TRACE, List.of("select:clarify-after-rejected-planning:" + phase(s)));
        }
        return update(RetailState.ROUTE, "abort", RetailState.TRACE,
                List.of("select:stop-after-planning-budget:" + phase(s)));
    }

    /** Resolve action authority using the typed allow-list. Model proposals never grant capability. */
    Map<String, Object> authorize(RetailState s) {
        Proposal proposal = s.selected().orElseThrow();
        var definition = actionRegistry.find(proposal.tool()).orElse(null);
        if (definition == null) {
            return update(RetailState.FAILURES, s.failures() + 1,
                    RetailState.APPROVAL_REQUIRED, true, RetailState.APPROVAL_STATUS, "REJECTED_UNKNOWN_ACTION",
                    RetailState.ROUTE, "finish", RetailState.FEEDBACK, "The proposed action is not registered.",
                    RetailState.TRACE, List.of("authorize:rejected-unknown:" + proposal.tool() + ":" + phase(s)));
        }
        boolean approvalRequired = definition.requiresHumanApproval();
        PendingActionApproval request = approvalRequired ? createApprovalRequest(s, definition, proposal) : null;
        return update(RetailState.APPROVAL_REQUIRED, approvalRequired,
                RetailState.APPROVAL_STATUS, approvalRequired ? "PENDING" : "NOT_REQUIRED",
                RetailState.APPROVAL_REQUEST, request, RetailState.APPROVAL_DECISION_ID, "",
                RetailState.APPROVAL_DECISION, "", RetailState.APPROVAL_ACTOR, "",
                RetailState.ROUTE, approvalRequired ? "approval" : "execute",
                RetailState.TRACE, List.of("authorize:" + proposal.tool() + ":risk=" + definition.risk()
                        + ":kind=" + definition.kind() + ":approval=" + approvalRequired + ":" + phase(s)));
    }

    /** Approval is checked again at the execution boundary, including actor and pending-state binding. */
    Map<String, Object> executeApproved(RetailState s) {
        Proposal selected = s.selected().orElse(null);
        var approvedDefinition = selected == null ? null : actionRegistry.find(selected.tool()).orElse(null);
        PendingActionApproval request = s.approvalRequest().orElse(null);
        if (!s.approvalRequired() || !"PENDING".equals(s.approvalStatus())
                || !s.customer().equals(s.approvalActor())
                || approvedDefinition == null || !approvedDefinition.requiresHumanApproval()
                || request == null || !request.approvalId().equals(s.approvalDecisionId())
                || !request.matches(approvedDefinition, actionArguments(selected), selected.rationale(), s.customer(),
                        "intention:" + sha256(s.intention().committedGoal()), s.intention().revision())) {
            return update(RetailState.FAILURES, s.failures() + 1,
                    RetailState.ROUTE, "finish",
                    RetailState.FEEDBACK, "Execution blocked: approval state or approving subject is invalid.",
                    RetailState.TRACE, List.of("execute:approval-invalid-or-subject-mismatch:" + phase(s)));
        }
        if (!"APPROVED".equals(s.approvalDecision().orElse(""))) {
            return update(RetailState.APPROVAL_STATUS, "DENIED", RetailState.ROUTE, "finish",
                    RetailState.FEEDBACK, "Action was not approved; no tool was executed.",
                    RetailState.TRACE, List.of("approval:denied-no-execution:" + phase(s)));
        }
        return executeAction(s, true);
    }

    /** Recheck the registry immediately before gateway invocation; earlier authorization is not a capability token. */
    Map<String, Object> execute(RetailState s) {
        Proposal selected = s.selected().orElse(null);
        var definition = selected == null ? null : actionRegistry.find(selected.tool()).orElse(null);
        if (s.approvalRequired() || definition == null || definition.requiresHumanApproval()) {
            return update(RetailState.FAILURES, s.failures() + 1, RetailState.ROUTE, "finish",
                    RetailState.FEEDBACK, "Execution blocked: action is missing, no longer authorized for this path, or requires explicit approval.",
                    RetailState.TRACE, List.of("execute:approval-path-bypass-blocked:" + phase(s)));
        }
        return executeAction(s, false);
    }

    private Map<String, Object> executeAction(RetailState s, boolean approved) {
        Proposal proposal = s.selected().orElseThrow();
        String actionId = proposal.tool();
        var currentDefinition = actionRegistry.find(actionId).orElse(null);
        if (currentDefinition == null || currentDefinition.requiresHumanApproval() != approved) {
            return update(RetailState.FAILURES, s.failures() + 1, RetailState.ROUTE, "finish",
                    RetailState.FEEDBACK, "Execution blocked: action authority changed before gateway invocation.",
                    RetailState.TRACE, List.of("execute:authority-revalidation-failed:" + phase(s)));
        }
        Map<String, String> arguments = actionArguments(proposal);
        String fingerprint = sha256(component(actionId) + component(s.customer())
                + canonicalArguments(arguments) + component(s.intention().revision()));
        String idempotencyKey = sha256(component(s.threadId()) + component(s.iteration())
                + component(s.planningRound()) + component(fingerprint));
        ActionInvocation invocation = new ActionInvocation(java.util.UUID.randomUUID().toString(), actionId,
                s.customer(), arguments, idempotencyKey, java.time.Instant.now(),
                "intention:" + sha256(s.intention().committedGoal()), s.intention().revision());
        ActionOutcome outcome = actionGateway.execute(invocation);
        ResourceBudget budget = s.budget().toolUsed();
        boolean failed = outcome.status() != ActionOutcome.Status.SUCCEEDED;
        int failures = s.failures() + (failed ? 1 : 0);
        String observation = outcome.summary();
        Map<String, Object> result = update(RetailState.LAST_ACTION_INVOCATION, invocation,
                RetailState.LAST_ACTION_OUTCOME, outcome,
                RetailState.BUDGET, budget,
                RetailState.FAILURES, failures,
                RetailState.APPROVAL_STATUS, approved ? "APPROVED_EXECUTED" : s.approvalStatus(),
                RetailState.ROUTE, "observe",
                RetailState.TRACE, List.of("execute:" + actionId + ":status=" + outcome.status()
                        + ":code=" + outcome.resultCode() + ":approved=" + approved + ":cycle=" + (s.iteration() + 1)));
        if (!observation.isBlank()) result.put(RetailState.OBSERVATIONS, List.of(observation));
        return result;
    }

    /**
     * Reflection can summarize, but its Boolean goalSatisfied claim is non-authoritative.
     * The compiled graph uses reflectAsync so remote inference does not block a graph node thread.
     */
    Map<String, Object> reflect(RetailState s) {
        return reflectAsync(s).join();
    }

    CompletableFuture<Map<String, Object>> reflectAsync(RetailState s) {
        ResourceBudget budget = s.budget();
        if (!budget.canCallModel()) {
            return CompletableFuture.completedFuture(reflectionUpdate(s, s.failures() + 1, budget,
                    "reflect:fallback", ""));
        }
        return llm.jsonTrackedAsync("REFLECT", Prompts.REFLECT,
                Prompts.user(s, limits.maxIterations(), null), LlmDtos.ReflectionDto.class,
                budget.modelCallsRemaining()).thenApply(result -> {
            ResourceBudget updatedBudget = budget.modelUsed(result.callsUsed());
            if (!result.succeeded() || result.value() == null) {
                return reflectionUpdate(s, s.failures() + 1, updatedBudget, "reflect:fallback", "");
            }
            String summary = result.value().summary() == null ? "" : result.value().summary();
            return reflectionUpdate(s, s.failures(), updatedBudget, "reflect", summary);
        });
    }

    private Map<String, Object> reflectionUpdate(RetailState s, int failures, ResourceBudget budget,
                                                   String tag, String summary) {
        boolean verified = EvidenceVerifier.goalSatisfied(s.query(), s.observations(), catalog);
        String last = s.observations().isEmpty() ? "" : s.observations().getLast();
        boolean toolFailure = last.startsWith("[error]") || last.contains("unknown product id");
        int nextIteration = s.iteration() + 1;
        boolean remaining = budget.canPlan() && budget.canUseTool() && nextIteration < limits.maxIterations();
        IntentionGovernor.Result governed = intentionGovernor.afterEvidence(s.intention(), verified, toolFailure, remaining);
        String route;
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
                RetailState.TRACE, List.of(tag + ":cycle=" + nextIteration,
                        "reflect:evidence-verified=" + verified + ":cycle=" + nextIteration,
                        "governor:" + governed.decision().name().toLowerCase() + ":cycle=" + nextIteration));
    }

    /** B2: typed child recovery with a protected parent goal and bounded attempts. */
    Map<String, Object> recover(RetailState s) {
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
                RetailState.TRACE, List.of("recover:typed=" + type.name().toLowerCase() + ":" + phase(s),
                        "recover:attempt=" + child.attempt() + ":" + phase(s),
                        "recover:parent-goal-protected:" + phase(s),
                        "recover:compilation-eligible=" + resolution.eligibleForCompilation() + ":" + phase(s),
                        "recover:status=" + (exhausted ? "unrecoverable" : "resolved") + ":" + phase(s)));
    }

    /** Learning is based on verified outcomes; failed or unverified attempts are not recorded as success. */
    Map<String, Object> learn(RetailState s) {
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

    /** Compatibility wrapper for direct tests; the compiled graph uses answerAsync. */
    Map<String, Object> answer(RetailState s) {
        return answerAsync(s).join();
    }

    /** Final prose is accepted only if it cites every verified product id and price and no unknown product id. */
    CompletableFuture<Map<String, Object>> answerAsync(RetailState s) {
        if (!s.clarification().isBlank()) {
            return CompletableFuture.completedFuture(update(RetailState.ANSWER, s.clarification(),
                    RetailState.TRACE, List.of("answer:clarify")));
        }
        ResourceBudget budget = s.budget();
        if (!budget.canCallModel()) {
            return CompletableFuture.completedFuture(answerUpdate(s, fallbackAnswer(s), s.failures() + 1,
                    budget, "answer:fallback"));
        }
        return llm.textTrackedAsync("ANSWER", Prompts.ANSWER, Prompts.user(s, limits.maxIterations(), null))
                .thenApply(result -> {
                    ResourceBudget updatedBudget = budget.modelUsed(result.callsUsed());
                    if (!result.succeeded()) {
                        return answerUpdate(s, fallbackAnswer(s), s.failures() + 1,
                                updatedBudget, "answer:fallback");
                    }
                    String candidate = result.value();
                    boolean grounded = groundedAnswer(candidate, s);
                    return answerUpdate(s, grounded ? candidate : fallbackAnswer(s), s.failures(),
                            updatedBudget, grounded ? "answer" : "answer:grounding-fallback");
                });
    }

    private Map<String, Object> answerUpdate(RetailState s, String text, int failures,
                                             ResourceBudget budget, String tag) {
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
                if (p == null || p.tool() == null || !(p.tool().equals("finish") || actionRegistry.find(p.tool()).isPresent())) continue;
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

    private static RuntimeException asRuntime(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof RuntimeException runtime) return runtime;
        return new IllegalStateException("asynchronous node failed", cause);
    }

    private PendingActionApproval createApprovalRequest(RetailState state, ActionDefinition definition, Proposal proposal) {
        Map<String, String> arguments = actionArguments(proposal);
        String intentionId = "intention:" + sha256(state.intention().committedGoal());
        String approvalId = sha256(component(state.threadId()) + component(state.customer())
                + component(definition.toString()) + canonicalArguments(arguments)
                + component(proposal.rationale()) + component(intentionId)
                + component(state.intention().revision()));
        return new PendingActionApproval(approvalId, definition.id(), definition.description(), definition.kind(),
                definition.risk(), definition.effects(), definition.destructive(), definition.irreversible(),
                definition.financial(), definition.externalSharing(), arguments, proposal.rationale(), state.customer(),
                intentionId, state.intention().revision(), java.time.Instant.now());
    }

    private static Map<String, String> actionArguments(Proposal proposal) {
        return switch (proposal.tool()) {
            case "search_catalog" -> Map.of("query", proposal.args());
            case "check_stock" -> Map.of("productId", proposal.args().trim());
            case "get_history" -> Map.of(); // subject comes from application state, never model arguments
            default -> Map.of();
        };
    }

    /** Unique cycle/planning coordinates keep repeated node visits visible in append-only traces. */
    private static String phase(RetailState state) {
        return "cycle=" + (state.iteration() + 1) + ":plan=" + state.planningRound();
    }

    private static String component(Object value) {
        String text = java.util.Objects.toString(value, "");
        return text.length() + ":" + text;
    }

    private static String canonicalArguments(Map<String, String> arguments) {
        StringBuilder canonical = new StringBuilder();
        new java.util.TreeMap<>(arguments).forEach((key, value) -> canonical
                .append(key.length()).append(':').append(key)
                .append(value.length()).append(':').append(value));
        return canonical.toString();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(java.util.Objects.requireNonNullElse(value, "").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
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
