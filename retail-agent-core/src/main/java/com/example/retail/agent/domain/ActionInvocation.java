package com.example.retail.agent.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** A single proposed invocation, bound to a declared action and an authenticated subject. */
public record ActionInvocation(
        String invocationId,
        String actionId,
        String subjectId,
        Map<String, String> arguments,
        String idempotencyKey,
        Instant requestedAt,
        String intentionId,
        long expectedIntentionRevision) implements Serializable {

    public ActionInvocation {
        invocationId = required(invocationId, "invocationId");
        actionId = required(actionId, "actionId");
        subjectId = required(subjectId, "subjectId");
        arguments = Map.copyOf(arguments == null ? Map.of() : arguments);
        idempotencyKey = required(idempotencyKey, "idempotencyKey");
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
        intentionId = required(intentionId, "intentionId");
        if (expectedIntentionRevision < 0) throw new IllegalArgumentException("expectedIntentionRevision must be non-negative");
        if (arguments.entrySet().stream().anyMatch(e -> e.getKey() == null || e.getKey().isBlank() || e.getValue() == null)) {
            throw new IllegalArgumentException("arguments must have non-blank keys and non-null values");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
