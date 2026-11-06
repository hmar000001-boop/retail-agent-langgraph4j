package com.example.retail.agent.domain;

import java.io.Serializable;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Versioned plan aggregate. A plan is a proposal until the controlling policy commits it. */
public record Plan(
        String id,
        String intentionId,
        long intentionRevision,
        String method,
        List<Step> steps,
        Status status,
        long revision) implements Serializable {

    public Plan {
        id = required(id, "id");
        intentionId = required(intentionId, "intentionId");
        method = required(method, "method");
        steps = List.copyOf(steps == null ? List.of() : steps);
        status = Objects.requireNonNull(status, "status");
        if (intentionRevision < 0 || revision < 0) throw new IllegalArgumentException("revisions must be non-negative");
        Set<String> ids = new HashSet<>();
        for (Step step : steps) {
            if (!ids.add(step.id())) throw new IllegalArgumentException("duplicate plan step id: " + step.id());
        }
        for (Step step : steps) {
            if (!ids.containsAll(step.dependsOn())) throw new IllegalArgumentException("step dependency references an unknown step: " + step.id());
            if (step.dependsOn().contains(step.id())) throw new IllegalArgumentException("step cannot depend on itself: " + step.id());
        }
        if (hasDependencyCycle(steps)) throw new IllegalArgumentException("plan dependencies must be acyclic");
    }

    private static boolean hasDependencyCycle(List<Step> steps) {
        java.util.Map<String, Step> byId = new java.util.HashMap<>();
        steps.forEach(step -> byId.put(step.id(), step));
        java.util.Set<String> visiting = new java.util.HashSet<>();
        java.util.Set<String> visited = new java.util.HashSet<>();
        for (Step step : steps) if (hasCycle(step.id(), byId, visiting, visited)) return true;
        return false;
    }

    private static boolean hasCycle(String id, java.util.Map<String, Step> byId,
                                    java.util.Set<String> visiting, java.util.Set<String> visited) {
        if (visited.contains(id)) return false;
        if (!visiting.add(id)) return true;
        for (String dependency : byId.get(id).dependsOn()) {
            if (hasCycle(dependency, byId, visiting, visited)) return true;
        }
        visiting.remove(id);
        visited.add(id);
        return false;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    public enum Status { PROPOSED, EVALUATED, SELECTED, RUNNING, BLOCKED, COMPLETED, FAILED, ABANDONED }

    public record Step(String id, String actionId, String description, List<String> dependsOn) implements Serializable {
        public Step {
            id = required(id, "step.id");
            actionId = required(actionId, "step.actionId");
            description = required(description, "step.description");
            dependsOn = List.copyOf(dependsOn == null ? List.of() : dependsOn);
            if (dependsOn.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("step dependencies must not be blank");
            }
        }
    }
}
