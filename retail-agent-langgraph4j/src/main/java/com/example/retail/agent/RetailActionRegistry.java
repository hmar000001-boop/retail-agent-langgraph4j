package com.example.retail.agent;

import com.example.retail.agent.domain.ActionDefinition;
import com.example.retail.agent.port.ActionRegistry;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Explicit executable action catalogue for the primary runtime. */
public final class RetailActionRegistry implements ActionRegistry {
    private final Map<String, ActionDefinition> definitions;

    public RetailActionRegistry() {
        Map<String, ActionDefinition> items = new LinkedHashMap<>();
        register(items, "search_catalog", "Search the local product catalogue", ActionDefinition.Effect.READ);
        register(items, "get_history", "Read the authenticated customer's order history", ActionDefinition.Effect.READ);
        register(items, "check_stock", "Read current stock and price for a product", ActionDefinition.Effect.READ);
        this.definitions = Map.copyOf(items);
    }

    private static void register(Map<String, ActionDefinition> into, String id, String description,
                                 ActionDefinition.Effect effect) {
        into.put(id, new ActionDefinition(id, description, ActionDefinition.ActionKind.INTERNAL,
                ActionDefinition.RiskLevel.LOW, java.util.List.of(effect), java.util.List.of(),
                Duration.ofSeconds(5), true, false, false, false, false, false));
    }

    @Override public Optional<ActionDefinition> find(String actionId) {
        return Optional.ofNullable(definitions.get(actionId));
    }

    @Override public Collection<ActionDefinition> availableTo(String subjectId) {
        if (subjectId == null || subjectId.isBlank()) return java.util.List.of();
        return definitions.values();
    }
}
