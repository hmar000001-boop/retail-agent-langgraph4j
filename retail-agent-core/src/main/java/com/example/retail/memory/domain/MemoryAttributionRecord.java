package com.example.retail.memory.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Per-memory, per-use outcome relation. A task-level success is never copied to every retrieved item. */
public record MemoryAttributionRecord(String id, String subjectId, String executionId, String useId, String outcomeId,
                                      Relation relation, String basis, List<String> evidenceIds,
                                      String attributedBy, Instant attributedAt) implements Serializable {
    public MemoryAttributionRecord {
        id = required(id, "id"); subjectId = required(subjectId, "subjectId"); executionId = required(executionId, "executionId");
        useId = required(useId, "useId"); outcomeId = required(outcomeId, "outcomeId");
        Objects.requireNonNull(relation, "relation"); basis = required(basis, "basis");
        evidenceIds = evidenceIds == null ? List.of() : evidenceIds.stream().map(v -> required(v, "evidenceId")).distinct().toList();
        attributedBy = required(attributedBy, "attributedBy"); Objects.requireNonNull(attributedAt, "attributedAt");
        if ((relation == Relation.SUPPORTED || relation == Relation.CONTRADICTED) && evidenceIds.isEmpty())
            throw new IllegalArgumentException("supported/contradicted attribution requires explicit evidence references");
    }
    /** Keep the first timestamp on an exact attribution replay after checkpoint recovery. */
    public boolean sameLogicalAttribution(MemoryAttributionRecord other) {
        return other != null && id.equals(other.id) && subjectId.equals(other.subjectId)
                && executionId.equals(other.executionId) && useId.equals(other.useId)
                && outcomeId.equals(other.outcomeId) && relation == other.relation
                && basis.equals(other.basis) && evidenceIds.equals(other.evidenceIds)
                && attributedBy.equals(other.attributedBy);
    }
    private static String required(String v, String n) { if (v == null || v.isBlank()) throw new IllegalArgumentException(n + " must not be blank"); return v.trim(); }
    public enum Relation { SUPPORTED, CONTRADICTED, IRRELEVANT, UNDETERMINED }
}
