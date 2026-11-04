package com.example.retail.agent;

import com.example.retail.core.AgentLimits;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Spring-bound resource settings; defaults match the LangChain4j runtime's shared demo limits. */
@ConfigurationProperties(prefix = "app.agent")
public record AgentSettings(
        @DefaultValue("3") int maxIterations,
        @DefaultValue("0.4") double clarifyThreshold,
        @DefaultValue("4") int maxFailures,
        @DefaultValue("3") int maxPlanningRounds,
        @DefaultValue("3") int maxToolCalls,
        @DefaultValue("8") int maxModelCalls,
        @DefaultValue("2") int maxRecoveryAttempts,
        @DefaultValue("180") int memoryTokenBudget,
        @DefaultValue("260") int workspaceTokenBudget) {
    public AgentLimits limits() {
        return new AgentLimits(maxIterations, maxPlanningRounds, maxFailures, maxToolCalls,
                maxModelCalls, maxRecoveryAttempts, memoryTokenBudget, workspaceTokenBudget);
    }
}
