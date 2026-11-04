package com.example.retail.core;

import java.io.Serializable;

/** Typed child recovery context; the parent committed goal is copied and remains protected during recovery. */
public record RecoverySubstate(FailureType failureType, String parentStateFingerprint,
                               String protectedGoal, int attempt, int attemptLimit,
                               String resolution, String status) implements Serializable {
    public enum FailureType { TOOL_CONTRACT, ITEM_NOT_FOUND, EVIDENCE_CONFLICT, TRANSIENT_PROVIDER, UNKNOWN }

    public static RecoverySubstate open(FailureType type, String fingerprint, String goal, int limit) {
        return new RecoverySubstate(type, fingerprint, goal, 1, limit, "", "OPEN");
    }

    public boolean canRetry() { return attempt < attemptLimit; }

    public RecoverySubstate resolved(String typedResolution) {
        return new RecoverySubstate(failureType, parentStateFingerprint, protectedGoal, attempt,
                attemptLimit, typedResolution, "RESOLVED");
    }

    public RecoverySubstate nextAttempt() {
        return new RecoverySubstate(failureType, parentStateFingerprint, protectedGoal, attempt + 1,
                attemptLimit, "", "OPEN");
    }
}
