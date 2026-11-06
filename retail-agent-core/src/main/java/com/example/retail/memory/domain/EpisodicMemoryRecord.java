package com.example.retail.memory.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** A bounded record of an interaction/event and its observed outcome, not a chat transcript. */
public record EpisodicMemoryRecord(
        String id, String subjectId, Instant occurredAt, String goal, String summary,
        Outcome outcome, List<String> evidenceIds, MemoryProvenance provenance,
        MemoryLifecycle lifecycle, Instant createdAt, Instant updatedAt, Instant retentionUntil)
        implements Serializable {
    public EpisodicMemoryRecord {
        id = required(id, "id"); subjectId = required(subjectId, "subjectId");
        Objects.requireNonNull(occurredAt, "occurredAt"); goal = required(goal, "goal");
        summary = required(summary, "summary"); Objects.requireNonNull(outcome, "outcome");
        evidenceIds = clean(evidenceIds); Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(lifecycle, "lifecycle"); Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt precedes createdAt");
        if (retentionUntil != null && retentionUntil.isBefore(createdAt)) throw new IllegalArgumentException("retentionUntil precedes creation");
    }
    public boolean retrievableAt(Instant now) {
        return lifecycle == MemoryLifecycle.ACTIVE && !now.isBefore(occurredAt)
                && (retentionUntil == null || !now.isAfter(retentionUntil));
    }
    /** Stable event identity comparison: wall-clock metadata is retained from the first write on replay. */
    public boolean sameLogicalEvent(EpisodicMemoryRecord other) {
        return other != null && id.equals(other.id) && subjectId.equals(other.subjectId)
                && goal.equals(other.goal) && summary.equals(other.summary) && outcome == other.outcome
                && evidenceIds.equals(other.evidenceIds) && provenance.kind() == other.provenance.kind()
                && provenance.sourceId().equals(other.provenance.sourceId())
                && provenance.description().equals(other.provenance.description());
    }
    public EpisodicMemoryRecord withLifecycle(MemoryLifecycle next, Instant at) {
        transition(lifecycle, next);
        return new EpisodicMemoryRecord(id, subjectId, occurredAt, goal, summary, outcome, evidenceIds,
                provenance, next, createdAt, at, retentionUntil);
    }
    private static void transition(MemoryLifecycle from, MemoryLifecycle to) {
        if (!from.mayTransitionTo(to)) throw new IllegalStateException("Invalid memory lifecycle transition: " + from + " -> " + to);
    }
    private static List<String> clean(List<String> values) {
        return values == null ? List.of() : values.stream().map(v -> required(v, "evidenceId")).distinct().toList();
    }
    private static String required(String v, String n) { if (v == null || v.isBlank()) throw new IllegalArgumentException(n + " must not be blank"); return v.trim(); }
    public enum Outcome { PENDING, VERIFIED_SUCCESS, VERIFIED_FAILURE, PARTIAL, UNKNOWN }
}
