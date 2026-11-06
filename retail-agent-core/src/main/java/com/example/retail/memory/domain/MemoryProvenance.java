package com.example.retail.memory.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/** Origin metadata; provenance is retained with the memory rather than reconstructed from prose. */
public record MemoryProvenance(Kind kind, String sourceId, String description, Instant capturedAt)
        implements Serializable {
    public MemoryProvenance {
        Objects.requireNonNull(kind, "kind");
        sourceId = required(sourceId, "sourceId");
        description = required(description, "description");
        Objects.requireNonNull(capturedAt, "capturedAt");
    }
    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
    public enum Kind { USER_DECLARATION, SYSTEM_OF_RECORD, VERIFIED_DOCUMENT, OBSERVED_INTERACTION, MODEL_PROPOSAL, HUMAN_REVIEW }
}
