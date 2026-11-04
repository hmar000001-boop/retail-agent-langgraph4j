package com.example.retail.core;

import java.io.Serializable;

public record MemoryCandidate(String id, String content, double activation,
                              double predictedRetrievalLatencyMs, double downstreamValue,
                              double maintenanceCost, int tokenCost) implements Serializable {
    public MemoryCandidate withOutcome(boolean useful) {
        double activationNext = clamp(activation + (useful ? 0.08 : -0.04));
        double valueNext = clamp(downstreamValue + (useful ? 0.10 : -0.05));
        return new MemoryCandidate(id, content, activationNext, predictedRetrievalLatencyMs,
                valueNext, maintenanceCost, tokenCost);
    }

    private static double clamp(double x) { return Math.max(0.0, Math.min(1.0, x)); }
}
