package com.example.retail.agent.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Typed execution result; completion is separate from verified postconditions. */
public record ActionOutcome(
        String invocationId,
        Status status,
        String resultCode,
        String summary,
        List<String> producedEvidenceIds,
        Instant completedAt,
        boolean effectsVerified) implements Serializable {

    public ActionOutcome {
        invocationId = required(invocationId, "invocationId");
        status = Objects.requireNonNull(status, "status");
        resultCode = required(resultCode, "resultCode");
        summary = Objects.requireNonNullElse(summary, "");
        producedEvidenceIds = List.copyOf(producedEvidenceIds == null ? List.of() : producedEvidenceIds);
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
        if (status != Status.SUCCEEDED && effectsVerified) {
            throw new IllegalArgumentException("effects cannot be verified for a non-successful action");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    public enum Status { SUCCEEDED, REJECTED, FAILED, TIMED_OUT, CANCELLED, UNKNOWN }
}
