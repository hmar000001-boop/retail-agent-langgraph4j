package com.example.retail.agent.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable, checkpointable description of the exact action a human is being asked to approve. */
public record PendingActionApproval(
        String approvalId,
        String actionId,
        String description,
        ActionDefinition.ActionKind kind,
        ActionDefinition.RiskLevel risk,
        List<ActionDefinition.Effect> effects,
        boolean destructive,
        boolean irreversible,
        boolean financial,
        boolean externalSharing,
        Map<String, String> arguments,
        String rationale,
        String subjectId,
        String intentionId,
        long expectedIntentionRevision,
        Instant requestedAt) implements Serializable {

    public PendingActionApproval {
        approvalId = required(approvalId, "approvalId");
        actionId = required(actionId, "actionId");
        description = required(description, "description");
        kind = Objects.requireNonNull(kind, "kind");
        risk = Objects.requireNonNull(risk, "risk");
        effects = List.copyOf(effects == null ? List.of() : effects);
        if (effects.isEmpty()) throw new IllegalArgumentException("effects must not be empty");
        arguments = Map.copyOf(arguments == null ? Map.of() : arguments);
        rationale = Objects.requireNonNullElse(rationale, "");
        subjectId = required(subjectId, "subjectId");
        intentionId = required(intentionId, "intentionId");
        if (expectedIntentionRevision < 0) throw new IllegalArgumentException("expectedIntentionRevision must be non-negative");
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
    }

    /** Checks that the live registry and selected proposal still match the exact request shown to the human. */
    public boolean matches(ActionDefinition definition, Map<String, String> expectedArguments, String expectedRationale,
                           String expectedSubjectId, String expectedIntentionId, long expectedRevision) {
        return definition != null
                && actionId.equals(definition.id())
                && description.equals(definition.description())
                && kind == definition.kind()
                && risk == definition.risk()
                && effects.equals(definition.effects())
                && destructive == definition.destructive()
                && irreversible == definition.irreversible()
                && financial == definition.financial()
                && externalSharing == definition.externalSharing()
                && arguments.equals(expectedArguments == null ? Map.of() : expectedArguments)
                && rationale.equals(Objects.requireNonNullElse(expectedRationale, ""))
                && subjectId.equals(expectedSubjectId)
                && intentionId.equals(expectedIntentionId)
                && expectedIntentionRevision == expectedRevision;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
