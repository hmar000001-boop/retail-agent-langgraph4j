package com.example.retail.agent;

import java.util.List;

public record AgentResult(String answer, boolean clarification, int iterations, int llmFailures,
                          List<String> trace, List<String> observations) {
    /** True when at least one LLM call failed and a deterministic fallback was used. */
    public boolean degraded() {
        return llmFailures > 0;
    }
}
