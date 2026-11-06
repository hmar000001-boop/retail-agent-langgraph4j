package com.example.retail.memory.domain;

/** Lifecycle is explicit: retrieval must never silently promote a candidate into trusted memory. */
public enum MemoryLifecycle {
    CANDIDATE, ACTIVE, SUPERSEDED, ARCHIVED, REJECTED, EXPIRED;

    public boolean mayTransitionTo(MemoryLifecycle next) {
        if (next == null || next == this) return false;
        return switch (this) {
            case CANDIDATE -> next == ACTIVE || next == REJECTED || next == ARCHIVED || next == EXPIRED;
            case ACTIVE -> next == SUPERSEDED || next == ARCHIVED || next == EXPIRED;
            case SUPERSEDED, REJECTED, EXPIRED -> next == ARCHIVED;
            case ARCHIVED -> false;
        };
    }
}
