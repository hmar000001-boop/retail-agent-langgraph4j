package com.example.retail.core;

import java.io.Serializable;
import java.util.List;

/** Durable-goal data model for the B4 teaching implementation; method is mutable by an authorized governor. */
public record IntentionRecord(
        String committedGoal,
        String rationale,
        String currentMethod,
        String continuationCondition,
        List<String> reconsiderationTriggers,
        String authority,
        String terminationStatus,
        long revision) implements Serializable {

    public IntentionRecord {
        reconsiderationTriggers = List.copyOf(reconsiderationTriggers == null ? List.of() : reconsiderationTriggers);
    }

    public static IntentionRecord start(String goal) {
        return new IntentionRecord(goal, "Satisfy the customer's explicit product constraints using verified catalogue evidence",
                "retrieve-and-verify", "Continue while evidence can still be obtained within budget",
                List.of("tool failure", "contradictory evidence", "no feasible candidate", "budget boundary"),
                "runtime-governor", "ACTIVE", 0L);
    }

    public IntentionRecord changeMethod(String nextMethod) {
        return new IntentionRecord(committedGoal, rationale, nextMethod, continuationCondition,
                reconsiderationTriggers, authority, terminationStatus, revision + 1);
    }

    public IntentionRecord terminate(String status) {
        return new IntentionRecord(committedGoal, rationale, currentMethod, continuationCondition,
                reconsiderationTriggers, authority, status, revision + 1);
    }
}
