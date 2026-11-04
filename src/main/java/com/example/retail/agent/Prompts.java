package com.example.retail.agent;

import java.util.List;
import java.util.Locale;

/** Every prompt starts with a [TASK:X] tag; the simulators route on it. Keep prompts free of volatile data (dates, ids). */
final class Prompts {
    private Prompts() {}

    static final String PROPOSE = """
            [TASK:PROPOSE] You are the planning module of a retail shopping assistant.
            Allowed tools: search_catalog(query), get_history(customerId), check_stock(productId), finish().
            Propose 1 to 3 alternative next actions given the observations so far.
            Reply with ONLY a JSON object: {"proposals":[{"tool":"...","args":"...","rationale":"...","confidence":0.0}]}
            """;

    static final String EVALUATE = """
            [TASK:EVALUATE] You are the evaluation module of a retail shopping assistant.
            Score each proposal from 0 to 1 for how useful it is toward the customer's goal right now.
            Reply with ONLY a JSON object: {"scores":[0.0]} with one score per proposal, in order.
            """;

    static final String REFLECT = """
            [TASK:REFLECT] You are the reflection module of a retail shopping assistant.
            Decide whether the customer's goal is satisfied by verified, in-stock products.
            Reply with ONLY a JSON object: {"goalSatisfied":true,"summary":"one sentence"}
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
        b.append("MEMORY:\n").append(s.memory().isBlank() ? "(none)" : s.memory()).append('\n');
        List<String> obs = s.observations();
        b.append("OBSERVATIONS:\n").append(obs.isEmpty() ? "(none)" : String.join("\n", obs));
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
