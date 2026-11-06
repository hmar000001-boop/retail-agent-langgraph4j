package com.example.retail.agent;

/** One logical model operation and the provider calls consumed by that operation only. */
public record LlmCallResult<T>(T value, int callsUsed, RuntimeException failure) {
    public LlmCallResult {
        if (callsUsed < 0) throw new IllegalArgumentException("callsUsed must not be negative");
        if (failure == null && value == null) throw new IllegalArgumentException("successful result must contain a value");
        if (failure != null && value != null) throw new IllegalArgumentException("failed result cannot contain a value");
    }
    public boolean succeeded() { return failure == null; }
}
