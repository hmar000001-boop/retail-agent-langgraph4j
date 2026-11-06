package com.example.retail.agent;

import java.util.List;
import java.util.Locale;

/** Every prompt starts with a [TASK:X] tag; the simulators route on it. Keep prompts free of volatile data (dates, ids). */
final class Prompts {
    private Prompts() {}

    static final String PROPOSE = """
            [TASK:PROPOSE] You are the planning module of a retail shopping assistant.
            Allowed tools: search_catalog(query), get_history(customerId), check_stock(productId), finish().
            Propose up to 5 alternative next actions given the current goal, evidence, workspace and prior rejection feedback.
            Do not claim an action is authorized or that the goal is satisfied. Confidence is only your own estimate.
            You may revise earlier candidates, ask for more retrieval, or propose finish only when the evidence supports it.
            Reply with ONLY a JSON object: {"proposals":[{"tool":"...","args":"...","rationale":"...","confidence":0.0}]}
            """;

    static final String REFLECT = """
            [TASK:REFLECT] You are the reflection module of a retail shopping assistant.
            Interpret the latest observation and summarize what changed, what remains uncertain, and what evidence may be needed next.
            Your goalSatisfied field is advisory only; the runtime independently verifies catalogue and stock evidence.
            Reply with ONLY a JSON object: {"goalSatisfied":false,"summary":"one sentence"}
            """;

    static final String ANSWER = """
            [TASK:ANSWER] You are a retail shopping assistant. Write a short recommendation for the customer,
            grounded ONLY in the observations. Mention product ids and prices. Do not invent products.
            """;

    static String user(RetailState s, int maxIterations, List<Proposal> proposals) {
        StringBuilder b = new StringBuilder();
        b.append("QUERY: ").append(s.query()).append('\n');
        b.append("CUSTOMER: ").append(s.customer()).append('\n');
        b.append("ITERATION: ").append(s.iteration()).append(" of ").append(maxIterations).append('\n');
        b.append("COMMITTED_INTENTION:\n").append(s.intention().committedGoal()).append('\n');
        b.append("CURRENT_METHOD: ").append(s.intention().currentMethod()).append('\n');
        b.append("UNCERTAINTY: ").append(s.uncertainty().provenance()).append("; rawConfidence=")
                .append(String.format(Locale.ROOT, "%.2f", s.uncertainty().probabilityCorrect()))
                .append("; calibrated=").append(s.uncertainty().calibrated()).append('\n');
        b.append("RESOURCE_BUDGET: planning=").append(s.budget().planningRoundsUsed()).append('/')
                .append(s.budget().planningRoundsLimit()).append(", tools=").append(s.budget().toolCallsUsed())
                .append('/').append(s.budget().toolCallsLimit()).append('\n');
        b.append("PLANNING_FEEDBACK: ").append(s.feedback().isBlank() ? "(none)" : s.feedback()).append('\n');
        b.append("BOUNDED_WORKSPACE:\n").append(s.memory().isBlank() ? "(none)" : s.memory()).append('\n');
        // Observation history is deliberately NOT serialized into prompts. The latest outcome
        // must first win workspace admission; otherwise history could bypass B3's budget.
        if (proposals != null) {
            b.append("\nPROPOSALS:");
            for (int i = 0; i < proposals.size(); i++) {
                Proposal p = proposals.get(i);
                b.append('\n').append(i).append(": ").append(p.tool()).append('(').append(p.args()).append(") conf=")
                        .append(String.format(Locale.ROOT, "%.2f", p.confidence()))
                        .append(" - ").append(p.rationale());
            }
        }
        return b.toString();
    }
}
