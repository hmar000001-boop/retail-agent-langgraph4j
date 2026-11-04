package com.example.retail.langchain;

import com.example.retail.agent.Proposal;
import com.example.retail.core.AdaptiveMemoryPolicy;
import com.example.retail.core.AgentLimits;
import com.example.retail.core.BoundedWorkspace;
import com.example.retail.core.CandidateEvaluator;
import com.example.retail.core.EvidenceVerifier;
import com.example.retail.core.IntentionGovernor;
import com.example.retail.core.IntentionRecord;
import com.example.retail.core.MemoryCandidate;
import com.example.retail.core.RecoverySubstate;
import com.example.retail.core.TypedRecoveryPolicy;
import com.example.retail.core.UncertaintyEstimate;
import com.example.retail.core.WorkspaceCandidate;
import com.example.retail.domain.EpisodicMemory;
import com.example.retail.domain.OrderHistory;
import com.example.retail.domain.Product;
import com.example.retail.domain.ProductCatalog;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.model.chat.ChatModel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * LangChain4j Agentic implementation over the same shared Java control policies as the LangGraph4j module.
 * The AgenticScope stores only JSON-friendly JDK values; the model never writes authoritative domain objects.
 */
public final class LangChainRetailRuntime {
    private static final double ACCEPT_SCORE = 0.55;
    private static final Pattern PRODUCT_ID = Pattern.compile("p-\\d+");

    private final AgentLimits limits;
    private final ProductCatalog catalog;
    private final OrderHistory orders;
    private final EpisodicMemory episodicMemory;
    private final Map<String, AdaptiveMemoryPolicy> adaptiveMemory = new ConcurrentHashMap<>();
    private final BoundedWorkspace workspacePolicy = new BoundedWorkspace();
    private final Map<String, Double> workspaceAdmissionScores = new ConcurrentHashMap<>();
    private final Map<String, WorkspaceCandidate> workspaceCandidatesById = new ConcurrentHashMap<>();
    private final IntentionGovernor intentionGovernor = new IntentionGovernor();
    private final TypedRecoveryPolicy recoveryPolicy = new TypedRecoveryPolicy();
    private final UntypedAgent system;

    public LangChainRetailRuntime(ChatModel chatModel, ProductCatalog catalog,
                                  OrderHistory orders, EpisodicMemory episodicMemory) {
        this(chatModel, catalog, orders, episodicMemory, AgentLimits.defaults());
    }

    public LangChainRetailRuntime(ChatModel chatModel, ProductCatalog catalog, OrderHistory orders,
                                  EpisodicMemory episodicMemory, AgentLimits limits) {
        this.limits = limits;
        this.catalog = catalog;
        this.orders = orders;
        this.episodicMemory = episodicMemory;

        var retrieve = AgenticServices.nonAiAgentBuilder(scope -> retrieve(scope))
                .name("retrieve")
                .description("Retrieves episodic and catalogue candidates and admits bounded workspace content")
                .outputKey("retrieveResult")
                .build();
        var reserveProposal = AgenticServices.nonAiAgentBuilder(scope -> reserveModelCall(scope, "proposal"))
                .name("reserveProposalModelCall")
                .description("Reserves a model-call budget or installs deterministic fallback proposals")
                .outputKey("proposalReservation")
                .build();
        ProposalAgent proposalModel = AgenticServices.agentBuilder(ProposalAgent.class)
                .chatModel(chatModel)
                .build();
        // Wrap the model-backed subagent with a small runtime node so the authoritative
        // transition trace records the actual proposal invocation. The reservation node
        // records budget governance; this node records that the model call was then made.
        var proposal = AgenticServices.nonAiAgentBuilder(scope -> {
                    trace(scope, "propose");
                    return proposalModel.propose(
                            read(scope, "query", ""),
                            read(scope, "customerId", "anonymous"),
                            read(scope, "observationsText", ""),
                            read(scope, "workspaceText", ""),
                            read(scope, "feedback", ""),
                            read(scope, "method", "retrieve-and-verify"));
                })
                .name("propose")
                .description("Invokes the proposal LLM after budget reservation and records the proposal transition")
                .outputKey("rawProposals")
                .build();
        var evaluate = AgenticServices.nonAiAgentBuilder(scope -> evaluate(scope))
                .name("evaluate")
                .description("Independently evaluates candidates without trusting model confidence")
                .outputKey("evaluationResult")
                .build();
        var select = AgenticServices.nonAiAgentBuilder(scope -> select(scope))
                .name("select")
                .description("Authorizes one action, rejects all candidates, or terminates safely")
                .outputKey("selectionResult")
                .build();
        var execute = AgenticServices.nonAiAgentBuilder(scope -> execute(scope))
                .name("execute")
                .description("Executes only whitelisted retail grounding actions")
                .outputKey("executionResult")
                .build();
        var reserveReflection = AgenticServices.nonAiAgentBuilder(scope -> reserveModelCall(scope, "reflection"))
                .name("reserveReflectionModelCall")
                .description("Reserves a model-call budget for an advisory interpretation of the latest observation")
                .outputKey("reflectionReservation")
                .build();
        ReflectionAgent reflection = AgenticServices.agentBuilder(ReflectionAgent.class)
                .chatModel(chatModel)
                .outputKey("advisoryReflection")
                .build();
        var reflect = AgenticServices.nonAiAgentBuilder(scope -> reflect(scope))
                .name("reflect")
                .description("Incorporates observations and lets external evidence govern goal completion")
                .outputKey("reflectionResult")
                .build();
        var recover = AgenticServices.nonAiAgentBuilder(scope -> recover(scope))
                .name("recover")
                .description("Opens a bounded typed recovery substate while protecting the committed goal")
                .outputKey("recoveryResult")
                .build();
        var learn = AgenticServices.nonAiAgentBuilder(scope -> learn(scope))
                .name("learn")
                .description("Stores outcomes and updates adaptive memory from observed downstream utility")
                .outputKey("learningResult")
                .build();
        var reserveAnswer = AgenticServices.nonAiAgentBuilder(scope -> reserveModelCall(scope, "answer"))
                .name("reserveAnswerModelCall")
                .description("Reserves the answer-generation model budget or falls back to grounded Java prose")
                .outputKey("answerReservation")
                .build();
        AnswerAgent answer = AgenticServices.agentBuilder(AnswerAgent.class)
                .chatModel(chatModel)
                .outputKey("draftAnswer")
                .build();
        var finalize = AgenticServices.nonAiAgentBuilder(scope -> finalizeAnswer(scope))
                .name("finalize")
                .description("Verifies generated answer grounding and returns the final response")
                .outputKey("answer")
                .build();

        this.system = AgenticServices.plannerBuilder()
                .subAgents(retrieve, reserveProposal, proposal, evaluate, select, execute,
                        reserveReflection, reflection, reflect, recover, learn, reserveAnswer, answer, finalize)
                .outputKey("resultEnvelope")
                .planner(RetailPlanner::new)
                .build();
    }

    public AgentResult ask(String customerId, String query) {
        Map<String, Object> input = new HashMap<>();
        input.put("customerId", customerId);
        input.put("query", query);
        input.put("observations", List.of());
        input.put("trace", List.of());
        input.put("feedback", "");
        input.put("planningRound", 0);
        input.put("iteration", 0);
        input.put("failures", 0);
        input.put("recoveryAttempts", 0);
        input.put("modelCalls", 0);
        input.put("toolCalls", 0);
        input.put("maxModelCalls", limits.maxModelCalls());
        input.put("maxToolCalls", limits.maxToolCalls());
        input.put("maxPlanningRounds", limits.maxPlanningRounds());
        input.put("maxIterations", limits.maxIterations());
        input.put("maxRecoveryAttempts", limits.maxRecoveryAttempts());
        input.put("intentGoal", query);
        input.put("intentRationale", "Satisfy the customer's explicit product constraints using verified catalogue evidence");
        input.put("method", "retrieve-and-verify");
        input.put("intentTermination", "ACTIVE");
        input.put("intentAuthority", "runtime-governor");
        input.put("intentRevision", 0);
        input.put("intentContinuation", "Continue while evidence can still be obtained within budget");
        input.put("intentTriggers", List.of("tool failure", "contradictory evidence", "no feasible candidate", "budget boundary"));
        input.put("uncertainty", 0.0);
        input.put("uncertaintyProvenance", "no proposal evaluated yet");
        input.put("uncertaintyCalibrated", false);
        input.put("clarification", "");
        input.put("goalSatisfied", false);
        input.put("degraded", false);
        Object result = system.invoke(input);
        if (!(result instanceof Map<?, ?> envelope)) {
            return new AgentResult(String.valueOf(result), false, 0, 0, List.of("answer:unexpected-result"), List.of());
        }
        Object traceValue = envelope.get("trace");
        Object observationsValue = envelope.get("observations");
        List<String> trace = traceValue instanceof List<?> values ? values.stream().map(String::valueOf).toList() : List.of();
        List<String> observations = observationsValue instanceof List<?> values ? values.stream().map(String::valueOf).toList() : List.of();
        int iterations = envelope.get("iterations") instanceof Number value ? value.intValue() : 0;
        int failures = envelope.get("failures") instanceof Number value ? value.intValue() : 0;
        boolean clarification = Boolean.TRUE.equals(envelope.get("clarification"));
        return new AgentResult(String.valueOf(envelope.get("answer")), clarification, iterations, failures, trace, observations);
    }

    // ---------------------------------------------------------------- retrieval, workspace and B1/B3 admission

    private String retrieve(AgenticScope scope) {
        String customer = read(scope, "customerId", "anonymous");
        String query = read(scope, "query", "");
        AdaptiveMemoryPolicy memoryPolicy = adaptiveMemory.computeIfAbsent(customer, ignored -> new AdaptiveMemoryPolicy());
        for (String note : episodicMemory.recent(customer, 5)) {
            String id = "episode-" + Integer.toHexString((customer + note).hashCode());
            memoryPolicy.remember(new MemoryCandidate(id, "Previously: " + note,
                    0.65, 30, 0.55, 0.15, tokenCost(note)));
        }
        for (Product product : catalog.search(query, 5)) {
            memoryPolicy.remember(new MemoryCandidate("catalog-" + product.id(),
                    product.id() + " " + product.name() + ". " + product.description() + " ($" + product.price() + ")",
                    0.75, 20, 0.70, 0.10, tokenCost(product.description())));
        }
        List<MemoryCandidate> selectedMemory = memoryPolicy.select(limits.memoryTokenBudget());
        List<WorkspaceCandidate> candidates = new ArrayList<>();
        candidates.add(withWorkspaceScore(new WorkspaceCandidate("intention", WorkspaceCandidate.CandidateType.ACTIVE_INTENTION,
                "Committed goal: " + read(scope, "intentGoal", query) + " | method: " + read(scope, "method", "retrieve-and-verify"),
                1.0, 28, List.of("proposal", "evaluation", "selection", "execution", "monitor"))));
        for (MemoryCandidate item : selectedMemory) {
            candidates.add(withWorkspaceScore(new WorkspaceCandidate("memory:" + item.id(), WorkspaceCandidate.CandidateType.MEMORY,
                    item.content(), memoryUtility(item), item.tokenCost(), List.of("proposal", "evaluation", "selection"))));
        }
        List<String> observations = strings(scope, "observations");
        if (!observations.isEmpty()) {
            String last = observations.getLast();
            candidates.add(withWorkspaceScore(new WorkspaceCandidate("latest-observation-" + Integer.toHexString(last.hashCode()),
                    WorkspaceCandidate.CandidateType.TOOL_RESULT, last, 0.98, tokenCost(last),
                    List.of("proposal", "evaluation", "selection", "monitor"))));
        }
        String feedback = read(scope, "feedback", "");
        if (!feedback.isBlank()) candidates.add(withWorkspaceScore(new WorkspaceCandidate("planning-feedback-" + Integer.toHexString(feedback.hashCode()),
                WorkspaceCandidate.CandidateType.MONITOR, feedback, 0.95, tokenCost(feedback),
                List.of("proposal", "evaluation", "selection"))));
        BoundedWorkspace.Admission admission = workspacePolicy.admit(candidates, limits.workspaceTokenBudget());
        String workspace = admission.admitted().stream().map(WorkspaceCandidate::content).collect(Collectors.joining("\n"));
        scope.writeState("workspaceText", workspace.isBlank() ? "(empty bounded workspace)" : workspace);
        scope.writeState("observationsText", String.join("\n", observations));
        scope.writeState("lastObservation", observations.isEmpty() ? "" : observations.getLast());
        scope.writeState("selectedMemoryIds", selectedMemory.stream().map(MemoryCandidate::id).toList());
        scope.writeState("admittedWorkspace", admission.admitted().stream().map(WorkspaceCandidate::id).toList());
        Map<String, List<String>> broadcast = new HashMap<>();
        admission.admitted().forEach(item -> broadcast.put(item.id(), item.broadcastRecipients()));
        scope.writeState("workspaceBroadcast", broadcast);
        scope.writeState("trace", append(strings(scope, "trace"), "retrieve",
                "workspace:admit=" + admission.admitted().size() + "/reject=" + admission.rejectedIds().size()
                        + "/winner=" + admission.winnerId()));
        return "admitted=" + admission.admitted().size();
    }

    private String reserveModelCall(AgenticScope scope, String purpose) {
        int used = number(scope, "modelCalls", 0);
        int limit = number(scope, "maxModelCalls", limits.maxModelCalls());
        boolean allowed = used < limit;
        String allowedKey = switch (purpose) {
            case "proposal" -> "proposalModelAllowed";
            case "reflection" -> "reflectionModelAllowed";
            default -> "answerModelAllowed";
        };
        scope.writeState(allowedKey, allowed);
        if (allowed) scope.writeState("modelCalls", used + 1);
        if (!allowed && purpose.equals("proposal")) {
            scope.writeState("rawProposals", ProposalCodec.encode(fallbackProposals(scope)));
            scope.writeState("degraded", true);
        }
        if (!allowed && purpose.equals("reflection")) {
            scope.writeState("advisoryReflection", "No model interpretation available; continue with deterministic evidence monitoring.");
            scope.writeState("degraded", true);
        }
        if (purpose.equals("reflection")) {
            List<String> observations = strings(scope, "observations");
            scope.writeState("observationsText", String.join("\n", observations));
            scope.writeState("lastObservation", observations.isEmpty() ? "" : observations.getLast());
        }
        if (purpose.equals("answer")) {
            String verified = EvidenceVerifier.verifiedProducts(read(scope, "query", ""), strings(scope, "observations"), catalog)
                    .stream().map(p -> p.id() + "|" + p.name() + "|" + p.price() + "|" + p.stock())
                    .collect(Collectors.joining("\n"));
            scope.writeState("verifiedProducts", verified);
        }
        if (!allowed && purpose.equals("answer")) {
            scope.writeState("draftAnswer", fallbackAnswer(scope));
            scope.writeState("degraded", true);
        }
        trace(scope, "budget:model:" + purpose + "=" + (allowed ? "reserved" : "exhausted"));
        return allowed ? "reserved" : "fallback";
    }

    // ---------------------------------------------------------------- evaluate and select (CoALA planning iteration)

    private String evaluate(AgenticScope scope) {
        List<Proposal> proposed = ProposalCodec.parse(read(scope, "rawProposals", ""));
        if (proposed.isEmpty()) proposed = fallbackProposals(scope);
        List<Proposal> scored = CandidateEvaluator.evaluateAll(proposed, read(scope, "query", ""),
                strings(scope, "observations"), catalog);
        scope.writeState("scoredProposalLines", ProposalCodec.encode(scored));
        double selfConfidence = scored.stream().max(Comparator.comparingDouble(Proposal::score))
                .map(Proposal::confidence).orElse(0.0);
        UncertaintyEstimate uncertainty = UncertaintyEstimate.uncalibratedModelEstimate(selfConfidence);
        scope.writeState("uncertainty", uncertainty.probabilityCorrect());
        scope.writeState("uncertaintyProvenance", uncertainty.provenance());
        scope.writeState("uncertaintyCalibrated", uncertainty.calibrated());
        scope.writeState("trace", append(strings(scope, "trace"), "evaluate", "evaluate:independent-evidence-policy"));
        return "evaluated=" + scored.size();
    }

    private String select(AgenticScope scope) {
        List<Proposal> scored = ProposalCodec.parse(read(scope, "scoredProposalLines", ""));
        int failures = number(scope, "failures", 0);
        if (failures >= 4 || scored.isEmpty()) {
            setDecision(scope, "ANSWER", "no authorized candidate remains");
            return "answer";
        }
        Proposal best = scored.stream().max(Comparator.comparingDouble(Proposal::score)).orElseThrow();
        List<String> observations = strings(scope, "observations");
        boolean verified = EvidenceVerifier.goalSatisfied(read(scope, "query", ""), observations, catalog);
        if (best.tool().equals("finish")) {
            if (verified) {
                scope.writeState("goalSatisfied", true);
                scope.writeState("intentTermination", "COMPLETED");
                scope.writeState("intentRevision", number(scope, "intentRevision", 0) + 1);
                setDecision(scope, "LEARN", "finish authorized by current matching stock evidence");
                trace(scope, "select:finish-authorized-by-evidence");
                return "learn";
            }
            return rejectAndReplan(scope, "finish rejected: no current matching in-stock evidence");
        }
        if (best.score() < ACCEPT_SCORE) return rejectAndReplan(scope, "all candidates rejected by independent evaluator");
        int toolCalls = number(scope, "toolCalls", 0);
        if (toolCalls >= number(scope, "maxToolCalls", limits.maxToolCalls())) {
            scope.writeState("intentTermination", "SUSPENDED_BUDGET");
            setDecision(scope, "ANSWER", "tool-call budget exhausted");
            trace(scope, "select:tool-budget-exhausted");
            return "answer";
        }
        scope.writeState("selectedTool", best.tool());
        scope.writeState("selectedArgs", best.args());
        scope.writeState("selectedScore", best.score());
        setDecision(scope, "EXECUTE", "independent evaluator authorized " + best.tool());
        trace(scope, "select:execute", "select:tool=" + best.tool(), "select:score=" + best.score());
        return "execute";
    }

    private String rejectAndReplan(AgenticScope scope, String reason) {
        int round = number(scope, "planningRound", 0);
        int limit = number(scope, "maxPlanningRounds", limits.maxPlanningRounds());
        if (round < limit) {
            scope.writeState("planningRound", round + 1);
            scope.writeState("feedback", reason);
            setDecision(scope, "REPLAN", reason);
            trace(scope, "select:reject-all:replan=" + (round + 1));
        } else {
            scope.writeState("clarification", "I could not verify a suitable product within the planning budget. Could you relax one constraint or clarify your preferred use?");
            setDecision(scope, "ANSWER", reason);
            trace(scope, "select:planning-budget-exhausted");
        }
        return read(scope, "decision", "REPLAN");
    }

    // ---------------------------------------------------------------- grounding, observation, B2/B4/B6

    private String execute(AgenticScope scope) {
        String tool = read(scope, "selectedTool", "");
        String args = read(scope, "selectedArgs", "");
        String customer = read(scope, "customerId", "anonymous");
        String observation = switch (tool) {
            case "search_catalog" -> search(args);
            case "get_history" -> history(customer);
            case "check_stock" -> stock(args);
            default -> "[error] unsupported tool " + tool;
        };
        scope.writeState("observations", append(strings(scope, "observations"), observation));
        scope.writeState("toolCalls", number(scope, "toolCalls", 0) + 1);
        if (observation.startsWith("[error]") || observation.contains("unknown product id")) {
            scope.writeState("failures", number(scope, "failures", 0) + 1);
        }
        scope.writeState("trace", append(strings(scope, "trace"), "execute:" + tool));
        return observation;
    }

    private String reflect(AgenticScope scope) {
        List<String> observations = strings(scope, "observations");
        String last = observations.isEmpty() ? "" : observations.getLast();
        boolean verified = EvidenceVerifier.goalSatisfied(read(scope, "query", ""), observations, catalog);
        boolean toolFailure = last.startsWith("[error]") || last.contains("unknown product id");
        int iteration = number(scope, "iteration", 0) + 1;
        scope.writeState("iteration", iteration);
        scope.writeState("goalSatisfied", verified);
        List<Product> verifiedProducts = EvidenceVerifier.verifiedProducts(read(scope, "query", ""), observations, catalog);
        scope.writeState("verifiedProductIds", verifiedProducts.stream().map(Product::id).toList());
        boolean remaining = iteration < number(scope, "maxIterations", limits.maxIterations())
                && number(scope, "toolCalls", 0) < number(scope, "maxToolCalls", limits.maxToolCalls())
                && number(scope, "planningRound", 0) <= number(scope, "maxPlanningRounds", limits.maxPlanningRounds());
        IntentionRecord intention = intention(scope);
        IntentionGovernor.Result result = intentionGovernor.afterEvidence(intention, verified, toolFailure, remaining);
        writeIntention(scope, result.intention());
        String route;
        if (toolFailure) route = "RECOVER";
        else if (verified) route = "LEARN";
        else if (remaining) route = "CONTINUE";
        else route = "ANSWER";
        scope.writeState("reflectRoute", route);
        String advisory = read(scope, "advisoryReflection", "");
        scope.writeState("trace", append(strings(scope, "trace"), "reflect",
                "reflect:advisory-received=" + !advisory.isBlank(),
                "reflect:evidence-verified=" + verified, "governor:" + result.decision().name().toLowerCase(Locale.ROOT)));
        return route;
    }

    private String recover(AgenticScope scope) {
        List<String> observations = strings(scope, "observations");
        String last = observations.isEmpty() ? "" : observations.getLast();
        RecoverySubstate.FailureType type = last.contains("unknown product")
                ? RecoverySubstate.FailureType.ITEM_NOT_FOUND
                : last.contains("contract") ? RecoverySubstate.FailureType.TOOL_CONTRACT
                : last.contains("conflict") ? RecoverySubstate.FailureType.EVIDENCE_CONFLICT
                : RecoverySubstate.FailureType.UNKNOWN;
        int attempts = number(scope, "recoveryAttempts", 0) + 1;
        scope.writeState("recoveryAttempts", attempts);
        String parentFingerprint = Integer.toHexString((read(scope, "intentGoal", "") + observations).hashCode());
        RecoverySubstate substate = RecoverySubstate.open(type, parentFingerprint,
                read(scope, "intentGoal", ""), number(scope, "maxRecoveryAttempts", limits.maxRecoveryAttempts()));
        var resolution = recoveryPolicy.resolve(substate);
        boolean exhausted = attempts >= number(scope, "maxRecoveryAttempts", limits.maxRecoveryAttempts())
                || type == RecoverySubstate.FailureType.UNKNOWN;
        scope.writeState("recoveryType", type.name());
        scope.writeState("recoveryParentFingerprint", parentFingerprint);
        scope.writeState("recoveryProtectedGoal", substate.protectedGoal());
        scope.writeState("recoveryResolution", resolution.type() + ": " + resolution.instruction());
        scope.writeState("recoveryStatus", exhausted ? "UNRECOVERABLE" : "RESOLVED");
        scope.writeState("method", "recovery/" + resolution.type());
        scope.writeState("intentRevision", number(scope, "intentRevision", 0) + 1);
        scope.writeState("feedback", resolution.instruction());
        if (exhausted) {
            scope.writeState("intentTermination", "FAILED_RECOVERY");
            scope.writeState("clarification", "A bounded recovery attempt could not safely resolve the tool failure. The original goal was preserved; please retry or adjust the request.");
        }
        scope.writeState("trace", append(strings(scope, "trace"), "recover:typed=" + type.name().toLowerCase(Locale.ROOT),
                "recover:parent-goal-protected", "recover:compilation-eligible=" + resolution.eligibleForCompilation(),
                "recover:status=" + (exhausted ? "unrecoverable" : "resolved")));
        return exhausted ? "UNRECOVERABLE" : resolution.type();
    }

    private String learn(AgenticScope scope) {
        String customer = read(scope, "customerId", "anonymous");
        String query = read(scope, "query", "");
        List<Product> verified = EvidenceVerifier.verifiedProducts(query, strings(scope, "observations"), catalog);
        String outcome = verified.isEmpty() ? "not verified" : "verified in-stock: " + verified.stream()
                .map(p -> p.id() + " $" + p.price()).collect(Collectors.joining(", "));
        episodicMemory.remember(customer, "asked '" + query + "' -> " + outcome);
        AdaptiveMemoryPolicy policy = adaptiveMemory.get(customer);
        if (policy != null) {
            boolean useful = !verified.isEmpty();
            for (String id : strings(scope, "selectedMemoryIds")) policy.recordDownstreamUse(id, useful);
        }
        int workspaceUpdates = 0;
        for (String id : strings(scope, "admittedWorkspace")) {
            WorkspaceCandidate admitted = workspaceCandidatesById.get(id);
            if (admitted != null) {
                WorkspaceCandidate updated = workspacePolicy.updateAfterOutcome(admitted, !verified.isEmpty());
                workspaceAdmissionScores.put(id, updated.admissionScore());
                workspaceCandidatesById.put(id, updated);
                workspaceUpdates++;
            }
        }
        scope.writeState("trace", append(strings(scope, "trace"), "learn:outcome=" + (verified.isEmpty() ? "unverified" : "verified"),
                "learn:workspace-admission-updated=" + workspaceUpdates));
        return outcome;
    }

    // ---------------------------------------------------------------- answer and authority boundaries

    private Map<String, Object> finalizeAnswer(AgenticScope scope) {
        List<Product> verified = EvidenceVerifier.verifiedProducts(read(scope, "query", ""), strings(scope, "observations"), catalog);
        String draft = read(scope, "draftAnswer", fallbackAnswer(scope));
        String finalAnswer;
        if (!read(scope, "clarification", "").isBlank()) finalAnswer = read(scope, "clarification", "");
        else if (verified.isEmpty() || !isGrounded(draft, verified)) {
            finalAnswer = fallbackAnswer(scope);
            trace(scope, "answer:grounding-fallback");
        } else {
            finalAnswer = draft;
            trace(scope, "answer:model-grounded");
        }
        scope.writeState("answer", finalAnswer);
        trace(scope, "answer");
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("answer", finalAnswer);
        envelope.put("trace", strings(scope, "trace"));
        envelope.put("observations", strings(scope, "observations"));
        envelope.put("iterations", number(scope, "iteration", 0));
        envelope.put("failures", number(scope, "failures", 0));
        envelope.put("clarification", !read(scope, "clarification", "").isBlank());
        envelope.put("degraded", Boolean.TRUE.equals(scope.readState("degraded", false)));
        scope.writeState("resultEnvelope", Map.copyOf(envelope));
        return Map.copyOf(envelope);
    }

    private boolean isGrounded(String draft, List<Product> verified) {
        if (draft == null || draft.isBlank()) return false;
        for (Product product : verified) {
            if (!draft.contains(product.id()) || !draft.contains(String.valueOf(product.price()))) return false;
        }
        Matcher matcher = PRODUCT_ID.matcher(draft);
        while (matcher.find()) if (verified.stream().noneMatch(p -> p.id().equals(matcher.group()))) return false;
        return true;
    }

    private String fallbackAnswer(AgenticScope scope) {
        String clarification = read(scope, "clarification", "");
        if (!clarification.isBlank()) return clarification;
        List<Product> verified = EvidenceVerifier.verifiedProducts(read(scope, "query", ""), strings(scope, "observations"), catalog);
        if (verified.isEmpty()) return "I could not verify a current in-stock product satisfying all constraints. "
                + "Please relax one constraint or clarify your preferred use.";
        return "Based on current catalogue verification, I recommend: " + verified.stream()
                .map(p -> p.name() + " (" + p.id() + ") at $" + p.price() + "; " + p.stock() + " units shown in stock")
                .collect(Collectors.joining(". ")) + ".";
    }

    private List<Proposal> fallbackProposals(AgenticScope scope) {
        List<String> observations = strings(scope, "observations");
        boolean searched = observations.stream().anyMatch(o -> o.startsWith("[search_catalog]"));
        boolean checked = observations.stream().anyMatch(o -> o.startsWith("[check_stock]"));
        if (!searched) return List.of(new Proposal("search_catalog", read(scope, "query", ""), "fallback: retrieve current catalogue candidates", 0.5, 0));
        String first = firstProductId(observations);
        if (!checked && !first.isBlank()) return List.of(new Proposal("check_stock", first, "fallback: verify current stock and price", 0.5, 0));
        if (EvidenceVerifier.goalSatisfied(read(scope, "query", ""), observations, catalog))
            return List.of(new Proposal("finish", "", "fallback: independent evidence verifier confirms the goal", 0.5, 0));
        return List.of(new Proposal("search_catalog", read(scope, "query", "") + " alternatives", "fallback: search alternatives", 0.5, 0));
    }

    // ---------------------------------------------------------------- tools and scope utilities

    private String search(String query) {
        String matches = catalog.search(query, 3).stream().map(p -> p.id() + "|" + p.name() + "|" + p.price())
                .collect(Collectors.joining("; "));
        return "[search_catalog] \"" + query + "\" -> " + (matches.isBlank() ? "no results" : matches);
    }

    private String history(String customer) {
        String names = orders.productIds(customer).stream().map(catalog::byId).flatMap(java.util.Optional::stream)
                .map(p -> p.id() + " " + p.name()).collect(Collectors.joining("; "));
        return "[get_history] " + customer + " bought: " + (names.isBlank() ? "nothing yet" : names);
    }

    private String stock(String rawId) {
        Matcher matcher = PRODUCT_ID.matcher(rawId == null ? "" : rawId);
        if (!matcher.find()) return "[check_stock] unknown product id '" + rawId + "'";
        String id = matcher.group();
        return catalog.byId(id).map(p -> "[check_stock] " + p.id() + " stock=" + p.stock() + " price=" + p.price())
                .orElse("[check_stock] unknown product id '" + id + "'");
    }

    private static String firstProductId(List<String> observations) {
        for (int i = observations.size() - 1; i >= 0; i--) {
            if (!observations.get(i).startsWith("[search_catalog]")) continue;
            Matcher matcher = PRODUCT_ID.matcher(observations.get(i));
            if (matcher.find()) return matcher.group();
        }
        return "";
    }

    private static void setDecision(AgenticScope scope, String value, String reason) {
        scope.writeState("decision", value);
        scope.writeState("decisionReason", reason);
    }

    private static IntentionRecord intention(AgenticScope scope) {
        return new IntentionRecord(read(scope, "intentGoal", ""), read(scope, "intentRationale", ""),
                read(scope, "method", "retrieve-and-verify"), read(scope, "intentContinuation", ""),
                strings(scope, "intentTriggers"),
                read(scope, "intentAuthority", "runtime-governor"), read(scope, "intentTermination", "ACTIVE"),
                number(scope, "intentRevision", 0));
    }

    private static void writeIntention(AgenticScope scope, IntentionRecord record) {
        scope.writeState("intentGoal", record.committedGoal());
        scope.writeState("intentRationale", record.rationale());
        scope.writeState("method", record.currentMethod());
        scope.writeState("intentAuthority", record.authority());
        scope.writeState("intentContinuation", record.continuationCondition());
        scope.writeState("intentTriggers", record.reconsiderationTriggers());
        scope.writeState("intentTermination", record.terminationStatus());
        scope.writeState("intentRevision", record.revision());
    }

    private WorkspaceCandidate withWorkspaceScore(WorkspaceCandidate candidate) {
        double score = workspaceAdmissionScores.getOrDefault(candidate.id(), candidate.admissionScore());
        WorkspaceCandidate scored = new WorkspaceCandidate(candidate.id(), candidate.type(), candidate.content(), score,
                candidate.tokenCost(), candidate.broadcastRecipients());
        workspaceCandidatesById.put(scored.id(), scored);
        return scored;
    }

    private static int tokenCost(String text) { return Math.max(8, (text == null ? 0 : text.length()) / 4); }
    private static double memoryUtility(MemoryCandidate item) {
        return (item.activation() * item.downstreamValue()) /
                (1.0 + item.predictedRetrievalLatencyMs() / 100.0 + item.maintenanceCost());
    }
    private static String read(AgenticScope scope, String key, String fallback) {
        String value = scope.readState(key, fallback);
        return value == null ? fallback : value;
    }
    private static int number(AgenticScope scope, String key, int fallback) {
        Object value = scope.readState(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }
    private static List<String> strings(AgenticScope scope, String key) {
        Object value = scope.readState(key);
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().map(String::valueOf).toList();
    }
    private static List<String> append(List<String> original, String... additions) {
        List<String> result = new ArrayList<>(original);
        result.addAll(List.of(additions));
        return List.copyOf(result);
    }
    private static void trace(AgenticScope scope, String... events) {
        scope.writeState("trace", append(strings(scope, "trace"), events));
    }
}
