package com.example.retail.agent.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * A sourced, time-bounded assertion available to the agent.
 * Evidence is not automatically authoritative: consumers must inspect authority,
 * scope, freshness and provenance before using it to justify a decision.
 */
public record Evidence(
        String id,
        String subject,
        String predicate,
        String value,
        EvidenceSource source,
        Authority authority,
        String scope,
        Instant observedAt,
        Instant expiresAt,
        String provenance) implements Serializable {

    public Evidence {
        id = required(id, "id");
        subject = required(subject, "subject");
        predicate = required(predicate, "predicate");
        value = required(value, "value");
        source = Objects.requireNonNull(source, "source");
        authority = Objects.requireNonNull(authority, "authority");
        scope = required(scope, "scope");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        provenance = required(provenance, "provenance");
        if (expiresAt != null && expiresAt.isBefore(observedAt)) {
            throw new IllegalArgumentException("expiresAt must not precede observedAt");
        }
    }

    public boolean isFreshAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return !instant.isBefore(observedAt) && (expiresAt == null || !instant.isAfter(expiresAt));
    }

    /** Same immutable assertion, ignoring timestamps that may differ when a checkpoint replays its writer. */
    public boolean sameAssertion(Evidence other) {
        return other != null && id.equals(other.id) && subject.equals(other.subject)
                && predicate.equals(other.predicate) && value.equals(other.value)
                && source == other.source && authority == other.authority
                && scope.equals(other.scope) && provenance.equals(other.provenance);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    public enum EvidenceSource { USER, FIRST_PARTY_SYSTEM, EXTERNAL_SYSTEM, RETRIEVED_DOCUMENT, MODEL_INFERENCE }

    /** Authority describes who/what is entitled to establish a claim in a given scope. */
    public enum Authority { USER_DECLARED, SYSTEM_OF_RECORD, VERIFIED_SOURCE, UNVERIFIED_SOURCE, MODEL_GENERATED }
}
