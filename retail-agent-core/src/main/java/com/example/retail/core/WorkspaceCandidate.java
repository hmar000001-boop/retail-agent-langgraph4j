package com.example.retail.core;

import java.io.Serializable;
import java.util.List;

public record WorkspaceCandidate(String id, CandidateType type, String content,
                                 double admissionScore, int tokenCost,
                                 List<String> broadcastRecipients) implements Serializable {
    public enum CandidateType { MEMORY, RETRIEVED_EVIDENCE, ACTIVE_INTENTION, TOOL_RESULT, PLAN, MONITOR }
    public WorkspaceCandidate {
        broadcastRecipients = List.copyOf(broadcastRecipients == null ? List.of() : broadcastRecipients);
    }
}
