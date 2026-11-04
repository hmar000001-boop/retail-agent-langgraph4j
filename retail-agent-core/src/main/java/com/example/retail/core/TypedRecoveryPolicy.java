package com.example.retail.core;

/** B2-inspired bounded recovery policy. Compilation is only a candidate until repeated safe transfer is demonstrated. */
public final class TypedRecoveryPolicy {
    public record Resolution(String type, String instruction, boolean eligibleForCompilation) {}

    public Resolution resolve(RecoverySubstate substate) {
        return switch (substate.failureType()) {
            case ITEM_NOT_FOUND -> new Resolution("ALTERNATIVE_EVIDENCE_SOURCE", "retrieve alternatives; do not alter the committed goal", false);
            case TOOL_CONTRACT -> new Resolution("RETRY_WITH_VALIDATED_ARGUMENT", "rebuild arguments from the typed tool contract", false);
            case EVIDENCE_CONFLICT -> new Resolution("REVERIFY_SOURCE_OF_TRUTH", "refresh the catalogue observation before deciding", false);
            case TRANSIENT_PROVIDER -> new Resolution("BOUNDED_RETRY", "retry once within remaining budget", false);
            case UNKNOWN -> new Resolution("ESCALATE", "stop automatic recovery and return a bounded failure", false);
        };
    }
}
