package com.example.retail.core;

import java.io.Serializable;

/** Shared per-run ceilings so framework comparisons use the same resource envelope. */
public record AgentLimits(int maxIterations, int maxPlanningRounds, int maxFailures,
                          int maxToolCalls, int maxModelCalls, int maxRecoveryAttempts,
                          int memoryTokenBudget, int workspaceTokenBudget) implements Serializable {
    public AgentLimits {
        if (maxIterations < 1 || maxPlanningRounds < 0 || maxFailures < 1
                || maxToolCalls < 1 || maxModelCalls < 1 || maxRecoveryAttempts < 1
                || memoryTokenBudget < 1 || workspaceTokenBudget < 1
                || maxIterations > 1_000 || maxPlanningRounds > 1_000 || maxFailures > 1_000
                || maxToolCalls > 1_000 || maxModelCalls > 1_000 || maxRecoveryAttempts > 1_000
                || memoryTokenBudget > 100_000 || workspaceTokenBudget > 100_000) {
            throw new IllegalArgumentException("Resource limits must be positive (planning rounds may be zero) and within the supported ceiling");
        }
    }

    public static AgentLimits defaults() {
        return new AgentLimits(3, 3, 4, 3, 8, 2, 180, 260);
    }
}
