package com.example.retail.agent;

import com.example.retail.agent.domain.PendingActionApproval;
import java.util.List;
import java.util.Optional;

/** Immutable application result, including the graph thread and exact request needed for approval resumption. */
public record AgentResult(String answer, boolean clarification, int iterations, int llmFailures,
                          List<String> trace, List<String> observations, String threadId,
                          boolean awaitingApproval, String approvalStatus,
                          PendingActionApproval pendingApprovalRequest) {
    public AgentResult {
        trace = List.copyOf(trace == null ? List.of() : trace);
        observations = List.copyOf(observations == null ? List.of() : observations);
        threadId = threadId == null ? "" : threadId;
        approvalStatus = approvalStatus == null ? "NOT_REQUIRED" : approvalStatus;
        if (!awaitingApproval && pendingApprovalRequest != null) {
            throw new IllegalArgumentException("pendingApprovalRequest is only present while awaiting approval");
        }
        if (awaitingApproval && pendingApprovalRequest == null) {
            throw new IllegalArgumentException("awaiting approval requires a typed approval request");
        }
    }

    /** Source compatibility for callers that only consume completed results. */
    public AgentResult(String answer, boolean clarification, int iterations, int llmFailures,
                       List<String> trace, List<String> observations, String threadId,
                       boolean awaitingApproval, String approvalStatus) {
        this(answer, clarification, iterations, llmFailures, trace, observations, threadId,
                awaitingApproval, approvalStatus, null);
    }

    /** Legacy short constructor retained for callers that only consume completed results. */
    public AgentResult(String answer, boolean clarification, int iterations, int llmFailures,
                       List<String> trace, List<String> observations) {
        this(answer, clarification, iterations, llmFailures, trace, observations, "", false, "NOT_REQUIRED", null);
    }

    public Optional<PendingActionApproval> approvalRequest() {
        return Optional.ofNullable(pendingApprovalRequest);
    }

    /** True when at least one LLM call failed and a deterministic fallback was used. */
    public boolean degraded() { return llmFailures > 0; }
}
