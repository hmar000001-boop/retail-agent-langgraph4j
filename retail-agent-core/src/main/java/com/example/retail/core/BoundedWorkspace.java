package com.example.retail.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * B3-inspired scarce workspace: typed content candidates compete, admitted content is broadcast,
 * and downstream outcomes can update future admission scores. This is not merely a message bus.
 */
public final class BoundedWorkspace {
    public record Admission(List<WorkspaceCandidate> admitted, List<String> rejectedIds,
                            int tokenCost, String winnerId) {
        public Admission {
            admitted = List.copyOf(admitted);
            rejectedIds = List.copyOf(rejectedIds);
        }
    }

    public Admission admit(List<WorkspaceCandidate> candidates, int tokenBudget) {
        List<WorkspaceCandidate> ranked = new ArrayList<>(candidates);
        ranked.sort(Comparator.comparingDouble(WorkspaceCandidate::admissionScore).reversed());
        List<WorkspaceCandidate> admitted = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        int cost = 0;
        for (WorkspaceCandidate candidate : ranked) {
            if (cost + candidate.tokenCost() <= tokenBudget) {
                admitted.add(candidate);
                cost += candidate.tokenCost();
            } else rejected.add(candidate.id());
        }
        String winner = admitted.isEmpty() ? "" : admitted.getFirst().id();
        return new Admission(admitted, rejected, cost, winner);
    }

    /** Local admission-value update used by tests; not a paper-specified scoring equation. */
    public WorkspaceCandidate updateAfterOutcome(WorkspaceCandidate candidate, boolean useful) {
        double score = Math.max(0.0, Math.min(1.0, candidate.admissionScore() + (useful ? 0.08 : -0.05)));
        return new WorkspaceCandidate(candidate.id(), candidate.type(), candidate.content(), score,
                candidate.tokenCost(), candidate.broadcastRecipients());
    }
}
