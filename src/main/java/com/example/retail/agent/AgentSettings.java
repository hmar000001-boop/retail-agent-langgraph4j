package com.example.retail.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Budget / governance knobs. maxIterations: one cycle is 6 graph steps and I believe LangGraph4j's default
 * step limit is 25, so keep it at 3 unless you raise that limit (see README).
 */
@ConfigurationProperties(prefix = "app.agent")
public record AgentSettings(
        @DefaultValue("3") int maxIterations,
        @DefaultValue("0.4") double clarifyThreshold,
        @DefaultValue("4") int maxFailures) {}
