package com.example.retail.agent.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable event observed by the agent; raw payload remains explicitly untrusted. */
public record Observation(
        String id,
        String kind,
        String payload,
        Instant observedAt,
        List<String> evidenceIds,
        TrustLevel trustLevel) implements Serializable {

    public Observation {
        id = required(id, "id");
        kind = required(kind, "kind");
        payload = required(payload, "payload");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        evidenceIds = List.copyOf(evidenceIds == null ? List.of() : evidenceIds);
        if (evidenceIds.stream().anyMatch(evidenceId -> evidenceId == null || evidenceId.isBlank())) {
            throw new IllegalArgumentException("evidenceIds must contain non-blank identifiers");
        }
        trustLevel = Objects.requireNonNull(trustLevel, "trustLevel");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    public enum TrustLevel { TRUSTED, UNTRUSTED_EXTERNAL, USER_SUPPLIED, MODEL_GENERATED }
}
