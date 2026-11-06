package com.example.retail.agent.domain;

import java.io.Serializable;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Declarative contract for an action. It describes authority and effects, not execution code. */
public record ActionDefinition(
        String id,
        String description,
        ActionKind kind,
        RiskLevel risk,
        List<Effect> effects,
        List<String> preconditions,
        Duration timeout,
        boolean idempotent,
        boolean destructive,
        boolean irreversible,
        boolean financial,
        boolean externalSharing,
        boolean approvalRequiredByDefinition) implements Serializable {

    public ActionDefinition {
        id = required(id, "id");
        description = required(description, "description");
        kind = Objects.requireNonNull(kind, "kind");
        risk = Objects.requireNonNull(risk, "risk");
        effects = List.copyOf(effects == null ? List.of() : effects);
        if (effects.isEmpty()) {
            throw new IllegalArgumentException("effects must declare at least one effect; use READ for read-only actions");
        }
        preconditions = List.copyOf(preconditions == null ? List.of() : preconditions);
        if (preconditions.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("preconditions must not contain blank values");
        }
        timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
    }

    /** Conservative default: every non-READ effect and every high-impact or externally visible action needs approval. */
    public boolean requiresHumanApproval() {
        boolean stateChanging = effects.stream().anyMatch(effect -> effect != Effect.READ);
        return approvalRequiredByDefinition || kind == ActionKind.EXTERNAL || stateChanging
                || risk == RiskLevel.HIGH || risk == RiskLevel.CRITICAL
                || destructive || irreversible || financial || externalSharing;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    public enum ActionKind { INTERNAL, EXTERNAL }
    public enum RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }
    public enum Effect { READ, WRITE, COMMUNICATE, PURCHASE, DELETE, CHANGE_PERMISSION }
}
