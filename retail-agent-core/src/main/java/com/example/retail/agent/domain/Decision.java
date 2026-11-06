package com.example.retail.agent.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Auditable decision made by a named authority over explicit evidence references. */
public record Decision(
        String id,
        DecisionType type,
        String selectedOption,
        String rationale,
        List<String> evidenceIds,
        DecisionAuthority authority,
        Instant decidedAt,
        long stateRevision) implements Serializable {

    public Decision {
        id = required(id, "id");
        type = Objects.requireNonNull(type, "type");
        selectedOption = required(selectedOption, "selectedOption");
        rationale = required(rationale, "rationale");
        evidenceIds = List.copyOf(evidenceIds == null ? List.of() : evidenceIds);
        authority = Objects.requireNonNull(authority, "authority");
        decidedAt = Objects.requireNonNull(decidedAt, "decidedAt");
        if (stateRevision < 0) throw new IllegalArgumentException("stateRevision must be non-negative");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    public enum DecisionType { PROPOSAL_ACCEPTED, METHOD_SELECTED, ACTION_AUTHORIZED, ACTION_REJECTED, RECOVERY_ACCEPTED, STOPPED }
    public enum DecisionAuthority { AGENT_POLICY, USER, SYSTEM_POLICY }
}
