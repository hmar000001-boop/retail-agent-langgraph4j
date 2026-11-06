package com.example.retail.memory.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** A scoped, provenance-bearing fact with validity and explicit contradiction/supersession state. */
public record SemanticMemoryRecord(
        String id, String subjectId, String subject, String predicate, String value,
        double confidence, List<String> evidenceIds, MemoryProvenance provenance,
        MemoryLifecycle lifecycle, Instant validFrom, Instant validUntil,
        String supersedesId, Instant createdAt, Instant updatedAt) implements Serializable {
    public SemanticMemoryRecord {
        id = required(id, "id"); subjectId = required(subjectId, "subjectId");
        subject = required(subject, "subject"); predicate = required(predicate, "predicate"); value = required(value, "value");
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) throw new IllegalArgumentException("confidence must be finite and in [0,1]");
        evidenceIds = evidenceIds == null ? List.of() : evidenceIds.stream().map(v -> required(v, "evidenceId")).distinct().toList();
        Objects.requireNonNull(provenance, "provenance"); Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(validFrom, "validFrom"); Objects.requireNonNull(createdAt, "createdAt"); Objects.requireNonNull(updatedAt, "updatedAt");
        if (validUntil != null && validUntil.isBefore(validFrom)) throw new IllegalArgumentException("validUntil precedes validFrom");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt precedes createdAt");
        supersedesId = supersedesId == null || supersedesId.isBlank() ? null : supersedesId.trim();
        if (lifecycle == MemoryLifecycle.ACTIVE && evidenceIds.isEmpty()) throw new IllegalArgumentException("active semantic facts require evidence references");
    }
    /** Stable fact identity for append retries; lifecycle/evidence/timestamps evolve only through explicit service operations. */
    public boolean sameLogicalFact(SemanticMemoryRecord other) {
        return other != null && id.equals(other.id) && subjectId.equals(other.subjectId)
                && subject.equals(other.subject) && predicate.equals(other.predicate) && value.equals(other.value)
                && Double.compare(confidence, other.confidence) == 0
                && provenance.kind() == other.provenance.kind()
                && provenance.sourceId().equals(other.provenance.sourceId())
                && provenance.description().equals(other.provenance.description())
                && Objects.equals(supersedesId, other.supersedesId);
    }
    public boolean sameStoredVersion(SemanticMemoryRecord other) {
        return sameLogicalFact(other) && other != null && evidenceIds.equals(other.evidenceIds)
                && lifecycle == other.lifecycle && validFrom.equals(other.validFrom)
                && Objects.equals(validUntil, other.validUntil);
    }
    public boolean retrievableAt(Instant now) {
        return lifecycle == MemoryLifecycle.ACTIVE && !now.isBefore(validFrom) && (validUntil == null || !now.isAfter(validUntil));
    }
    public SemanticMemoryRecord withLifecycle(MemoryLifecycle next, Instant at) {
        if (!lifecycle.mayTransitionTo(next)) throw new IllegalStateException("Invalid memory lifecycle transition: " + lifecycle + " -> " + next);
        return new SemanticMemoryRecord(id, subjectId, subject, predicate, value, confidence, evidenceIds, provenance,
                next, validFrom, validUntil, supersedesId, createdAt, at);
    }
    private static String required(String v, String n) { if (v == null || v.isBlank()) throw new IllegalArgumentException(n + " must not be blank"); return v.trim(); }
}
