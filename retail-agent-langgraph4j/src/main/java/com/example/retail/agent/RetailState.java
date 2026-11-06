package com.example.retail.agent;

import com.example.retail.agent.domain.ActionInvocation;
import com.example.retail.agent.domain.ActionOutcome;
import com.example.retail.agent.domain.PendingActionApproval;
import com.example.retail.core.IntentionRecord;
import com.example.retail.core.MemoryCandidate;
import com.example.retail.core.RecoverySubstate;
import com.example.retail.core.ResourceBudget;
import com.example.retail.core.UncertaintyEstimate;
import com.example.retail.core.WorkspaceCandidate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

/**
 * Serializable graph working memory. Append-only channels are limited to observation and trace
 * history. Parallel retrieval branches write to distinct LastValue keys to avoid concurrent
 * writes to one channel.
 */
public class RetailState extends AgentState {
    public static final String THREAD_ID = "threadId";
    public static final String QUERY = "query";
    public static final String CUSTOMER = "customer";
    public static final String ITERATION = "iteration";
    public static final String PLANNING_ROUND = "planningRound";
    public static final String MEMORY = "memory";
    public static final String WORKSPACE = "workspace";
    public static final String OBSERVATIONS = "observations";
    public static final String PROPOSALS = "proposals";
    public static final String SELECTED = "selected";
    public static final String ROUTE = "route";
    public static final String FAILURES = "failures";
    public static final String CLARIFICATION = "clarification";
    public static final String SUMMARY = "summary";
    public static final String ANSWER = "answer";
    public static final String TRACE = "trace";
    public static final String FEEDBACK = "planningFeedback";
    public static final String INTENTION = "intention";
    public static final String UNCERTAINTY = "uncertainty";
    public static final String BUDGET = "budget";
    public static final String RECOVERY = "recovery";
    public static final String GOAL_SATISFIED = "goalSatisfied";
    public static final String VERIFIED_PRODUCT_IDS = "verifiedProductIds";
    public static final String EPISODIC_CANDIDATES = "episodicCandidates";
    public static final String CATALOG_CANDIDATES = "catalogCandidates";
    public static final String SEMANTIC_CANDIDATES = "semanticCandidates";
    public static final String PROCEDURAL_CANDIDATES = "proceduralCandidates";
    /** @deprecated replaced by two branch-specific fields to prevent concurrent list writes. */
    @Deprecated public static final String RETRIEVAL_CANDIDATES = "retrievalCandidates";
    public static final String APPROVAL_REQUIRED = "approvalRequired";
    public static final String APPROVAL_DECISION = "approvalDecision";
    public static final String APPROVAL_ACTOR = "approvalActor";
    public static final String APPROVAL_STATUS = "approvalStatus";
    public static final String APPROVAL_REQUEST = "approvalRequest";
    public static final String APPROVAL_DECISION_ID = "approvalDecisionId";
    public static final String LAST_ACTION_INVOCATION = "lastActionInvocation";
    public static final String LAST_ACTION_OUTCOME = "lastActionOutcome";

    public static final Map<String, Channel<?>> SCHEMA = Map.<String, Channel<?>>of(
            OBSERVATIONS, Channels.appender(ArrayList::new),
            TRACE, Channels.appender(ArrayList::new));

    public RetailState(Map<String, Object> init) { super(init); }

    public String threadId() { return this.<String>value(THREAD_ID).orElse(""); }
    public String query() { return this.<String>value(QUERY).orElse(""); }
    public String customer() { return this.<String>value(CUSTOMER).orElse(""); }
    public int iteration() { return this.<Integer>value(ITERATION).orElse(0); }
    public int planningRound() { return this.<Integer>value(PLANNING_ROUND).orElse(0); }
    public String memory() { return this.<String>value(MEMORY).orElse(""); }
    public List<WorkspaceCandidate> workspace() { return this.<List<WorkspaceCandidate>>value(WORKSPACE).orElse(List.of()); }
    public List<String> observations() { return this.<List<String>>value(OBSERVATIONS).orElse(List.of()); }
    public List<Proposal> proposals() { return this.<List<Proposal>>value(PROPOSALS).orElse(List.of()); }
    public Optional<Proposal> selected() { return this.<Proposal>value(SELECTED); }
    public String route() { return this.<String>value(ROUTE).orElse("abort"); }
    public int failures() { return this.<Integer>value(FAILURES).orElse(0); }
    public String clarification() { return this.<String>value(CLARIFICATION).orElse(""); }
    public String summary() { return this.<String>value(SUMMARY).orElse(""); }
    public String answer() { return this.<String>value(ANSWER).orElse(""); }
    public String feedback() { return this.<String>value(FEEDBACK).orElse(""); }
    public IntentionRecord intention() { return this.<IntentionRecord>value(INTENTION).orElse(IntentionRecord.start(query())); }
    public UncertaintyEstimate uncertainty() { return this.<UncertaintyEstimate>value(UNCERTAINTY).orElse(UncertaintyEstimate.unknown("not yet estimated")); }
    public ResourceBudget budget() { return this.<ResourceBudget>value(BUDGET).orElse(ResourceBudget.start(3, 3, 8)); }
    public Optional<RecoverySubstate> recovery() { return this.<RecoverySubstate>value(RECOVERY); }
    public boolean goalSatisfied() { return this.<Boolean>value(GOAL_SATISFIED).orElse(false); }
    public List<String> verifiedProductIds() { return this.<List<String>>value(VERIFIED_PRODUCT_IDS).orElse(List.of()); }
    public List<MemoryCandidate> episodicCandidates() { return this.<List<MemoryCandidate>>value(EPISODIC_CANDIDATES).orElse(List.of()); }
    public List<MemoryCandidate> catalogCandidates() { return this.<List<MemoryCandidate>>value(CATALOG_CANDIDATES).orElse(List.of()); }
    public List<MemoryCandidate> semanticCandidates() { return this.<List<MemoryCandidate>>value(SEMANTIC_CANDIDATES).orElse(List.of()); }
    public List<MemoryCandidate> proceduralCandidates() { return this.<List<MemoryCandidate>>value(PROCEDURAL_CANDIDATES).orElse(List.of()); }
    public List<MemoryCandidate> retrievalCandidates() {
        if (value(EPISODIC_CANDIDATES).isPresent() || value(CATALOG_CANDIDATES).isPresent()
                || value(SEMANTIC_CANDIDATES).isPresent() || value(PROCEDURAL_CANDIDATES).isPresent()) {
            var merged = new ArrayList<MemoryCandidate>(episodicCandidates());
            merged.addAll(semanticCandidates());
            merged.addAll(proceduralCandidates());
            merged.addAll(catalogCandidates());
            return List.copyOf(merged);
        }
        return this.<List<MemoryCandidate>>value(RETRIEVAL_CANDIDATES).orElse(List.of());
    }
    public boolean approvalRequired() { return this.<Boolean>value(APPROVAL_REQUIRED).orElse(false); }
    public Optional<String> approvalDecision() { return this.<String>value(APPROVAL_DECISION); }
    public String approvalActor() { return this.<String>value(APPROVAL_ACTOR).orElse(""); }
    public String approvalStatus() { return this.<String>value(APPROVAL_STATUS).orElse("NOT_REQUIRED"); }
    public Optional<PendingActionApproval> approvalRequest() { return this.<PendingActionApproval>value(APPROVAL_REQUEST); }
    public String approvalDecisionId() { return this.<String>value(APPROVAL_DECISION_ID).orElse(""); }
    public boolean pendingApproval() { return approvalRequired() && "PENDING".equals(approvalStatus()) && approvalRequest().isPresent(); }
    public Optional<ActionInvocation> lastActionInvocation() { return this.<ActionInvocation>value(LAST_ACTION_INVOCATION); }
    public Optional<ActionOutcome> lastActionOutcome() { return this.<ActionOutcome>value(LAST_ACTION_OUTCOME); }
    public List<String> trace() { return this.<List<String>>value(TRACE).orElse(List.of()); }
}
