package com.example.retail.memory.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/** Records exposure/consumption of one memory item by one downstream component. */
public record MemoryUseRecord(String id, String subjectId, String executionId,
                              String memoryId, Kind memoryKind, Stage stage,
                              String consumer, Instant occurredAt) implements Serializable {
    public MemoryUseRecord {
        id = required(id, "id"); subjectId = required(subjectId, "subjectId");
        executionId = required(executionId, "executionId"); memoryId = required(memoryId, "memoryId");
        Objects.requireNonNull(memoryKind, "memoryKind"); Objects.requireNonNull(stage, "stage");
        consumer = required(consumer, "consumer"); Objects.requireNonNull(occurredAt, "occurredAt");
    }
    /** Stable logical identity check used when a checkpoint replays the same node with a new wall-clock timestamp. */
    public boolean sameLogicalUse(MemoryUseRecord other) {
        return other != null && id.equals(other.id) && subjectId.equals(other.subjectId)
                && executionId.equals(other.executionId) && memoryId.equals(other.memoryId)
                && memoryKind == other.memoryKind && stage == other.stage && consumer.equals(other.consumer);
    }
    private static String required(String v, String n) { if (v == null || v.isBlank()) throw new IllegalArgumentException(n + " must not be blank"); return v.trim(); }
    public enum Kind { EPISODIC, SEMANTIC, PROCEDURAL }
    public enum Stage { RETRIEVED, ADMITTED_TO_WORKSPACE, CITED_AS_EVIDENCE, USED_IN_PLAN, USED_IN_ACTION, SHOWN_TO_USER }
}
