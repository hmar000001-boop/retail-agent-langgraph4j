package com.example.retail.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * B1-inspired adaptive memory bank. The utility formula is an explicit local heuristic for experimentation;
 * Fan & Lan do not prescribe a universal activation or latency equation.
 */
public final class AdaptiveMemoryPolicy {
    private final Map<String, MemoryCandidate> items = new ConcurrentHashMap<>();

    public void remember(MemoryCandidate candidate) {
        // Refresh the content/cost metadata without erasing outcome-adapted activation and value.
        items.compute(candidate.id(), (ignored, previous) -> previous == null ? candidate
                : new MemoryCandidate(candidate.id(), candidate.content(), previous.activation(),
                        candidate.predictedRetrievalLatencyMs(), previous.downstreamValue(),
                        candidate.maintenanceCost(), candidate.tokenCost()));
    }

    public List<MemoryCandidate> select(int tokenBudget) {
        List<MemoryCandidate> ranked = new ArrayList<>(items.values());
        ranked.sort(Comparator.comparingDouble(AdaptiveMemoryPolicy::utility).reversed());
        List<MemoryCandidate> selected = new ArrayList<>();
        int used = 0;
        for (MemoryCandidate item : ranked) {
            if (used + item.tokenCost() <= tokenBudget) {
                selected.add(item);
                used += item.tokenCost();
            }
        }
        return List.copyOf(selected);
    }

    public void recordDownstreamUse(String id, boolean useful) {
        items.computeIfPresent(id, (ignored, item) -> item.withOutcome(useful));
    }

    public List<MemoryCandidate> snapshot() { return List.copyOf(items.values()); }

    private static double utility(MemoryCandidate item) {
        return (item.activation() * item.downstreamValue())
                / (1.0 + item.predictedRetrievalLatencyMs() / 100.0 + item.maintenanceCost());
    }
}
