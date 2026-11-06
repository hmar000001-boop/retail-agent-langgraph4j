package com.example.retail.agent.port;

import com.example.retail.agent.domain.ActionDefinition;
import java.util.Collection;
import java.util.Optional;

/** Allow-listed action catalogue; model output must never create executable actions. */
public interface ActionRegistry {
    Optional<ActionDefinition> find(String actionId);
    Collection<ActionDefinition> availableTo(String subjectId);
}
