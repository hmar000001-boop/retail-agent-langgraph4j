package com.example.retail.langchain;

import java.util.List;

public record AgentResult(String answer, boolean clarification, int iterations, int failures,
                          List<String> trace, List<String> observations) {
    public AgentResult {
        trace = List.copyOf(trace == null ? List.of() : trace);
        observations = List.copyOf(observations == null ? List.of() : observations);
    }
}
