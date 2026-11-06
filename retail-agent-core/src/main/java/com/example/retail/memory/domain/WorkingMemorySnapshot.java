package com.example.retail.memory.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Execution-scoped working memory. LangGraph checkpointing persists graph execution state separately. */
public record WorkingMemorySnapshot(String subjectId, String executionId, long revision,
                                     Map<String, String> values, Instant updatedAt, Instant expiresAt)
        implements Serializable {
    public WorkingMemorySnapshot {
        subjectId = required(subjectId, "subjectId"); executionId = required(executionId, "executionId");
        if (revision < 0) throw new IllegalArgumentException("revision cannot be negative");
        values = values == null ? Map.of() : Map.copyOf(values);
        if (values.size() > 128) throw new IllegalArgumentException("working memory supports at most 128 entries");
        int totalChars = 0;
        for (var entry : values.entrySet()) {
            required(entry.getKey(), "working-memory key");
            Objects.requireNonNull(entry.getValue(), "working-memory value");
            if (entry.getKey().length() > 256 || entry.getValue().length() > 8192)
                throw new IllegalArgumentException("working-memory key/value exceeds its size limit");
            totalChars += entry.getKey().length() + entry.getValue().length();
        }
        if (totalChars > 32768) throw new IllegalArgumentException("working-memory snapshot exceeds 32 KiB of characters");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (expiresAt != null && expiresAt.isBefore(updatedAt)) throw new IllegalArgumentException("expiresAt precedes updatedAt");
    }
    public boolean isExpiredAt(Instant now) { return expiresAt != null && now.isAfter(expiresAt); }
    public WorkingMemorySnapshot next(Map<String, String> nextValues, Instant at, Instant nextExpiry) {
        return new WorkingMemorySnapshot(subjectId, executionId, revision + 1, nextValues, at, nextExpiry);
    }
    private static String required(String v, String n) { if (v == null || v.isBlank()) throw new IllegalArgumentException(n + " must not be blank"); return v.trim(); }
}
