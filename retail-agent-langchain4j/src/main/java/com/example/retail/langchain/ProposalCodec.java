package com.example.retail.langchain;

import com.example.retail.agent.Proposal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Tiny line codec keeps AgenticScope values JSON-serializable without custom-class allowlisting. */
final class ProposalCodec {
    private static final Set<String> ALLOWED = Set.of("search_catalog", "get_history", "check_stock", "finish");

    private ProposalCodec() {}

    static List<Proposal> parse(String raw) {
        List<Proposal> result = new ArrayList<>();
        for (String line : (raw == null ? "" : raw).split("\\R")) {
            String cleaned = line.trim();
            if (cleaned.isEmpty() || cleaned.startsWith("```")) continue;
            String[] fields = cleaned.split("\\|", -1);
            if (fields.length < 4 || !ALLOWED.contains(fields[0].trim())) continue;
            try {
                double confidence = clamp(Double.parseDouble(fields[3].trim()));
                double score = fields.length > 4 ? clamp(Double.parseDouble(fields[4].trim())) : 0.0;
                result.add(new Proposal(fields[0].trim(), fields[1].trim(), fields[2].trim(), confidence, score));
            } catch (NumberFormatException ignored) {
                // Invalid candidate is dropped; it never becomes an executable action.
            }
            if (result.size() == 5) break;
        }
        return List.copyOf(result);
    }

    static String encode(List<Proposal> proposals) {
        return proposals.stream().map(p -> String.join("|", p.tool(), safe(p.args()), safe(p.rationale()),
                Double.toString(p.confidence()), Double.toString(p.score()))).reduce((a, b) -> a + "\n" + b).orElse("");
    }

    private static String safe(String value) { return (value == null ? "" : value).replace('|', '/').replace('\n', ' '); }
    private static double clamp(double v) { return Math.max(0.0, Math.min(1.0, v)); }
}
