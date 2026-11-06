package com.example.retail.config;

import com.example.retail.agent.LlmGateway;
import java.util.Objects;

/** Provider-neutral model boundary used by the primary LangGraph4j runtime. */
public record AgentLlm(LlmGateway gateway) {
    public AgentLlm {
        Objects.requireNonNull(gateway, "gateway");
    }
}
