package com.example.retail.core;

import com.example.retail.agent.Proposal;
import com.example.retail.domain.ProductCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Independent deterministic evaluator. It deliberately does not use Proposal.confidence.
 * Scores are local engineering heuristics for this example, not equations prescribed by CoALA or Fan & Lan.
 */
public final class CandidateEvaluator {
    private static final Set<String> ALLOWED = Set.of("search_catalog", "get_history", "check_stock", "finish");
    private static final Pattern PRODUCT_ID = Pattern.compile("p-\\d+");

    private CandidateEvaluator() {}

    public static List<Proposal> evaluateAll(List<Proposal> proposals, String query,
                                            List<String> observations, ProductCatalog catalog) {
        List<Proposal> evaluated = new ArrayList<>();
        for (Proposal proposal : proposals) evaluated.add(proposal.withScore(evaluate(proposal, query, observations, catalog)));
        return List.copyOf(evaluated);
    }

    public static double evaluate(Proposal proposal, String query, List<String> observations, ProductCatalog catalog) {
        if (proposal == null || !ALLOWED.contains(proposal.tool())) return 0.0;
        boolean hasHistory = observations.stream().anyMatch(s -> s.startsWith("[get_history]"));
        return switch (proposal.tool()) {
            case "search_catalog" -> {
                if (alreadySearchedSameQuery(observations, proposal.args())) yield 0.15;
                List<String> previouslySeen = searchedProductIds(observations);
                List<com.example.retail.domain.Product> results = catalog.search(proposal.args(), 3);
                if (results.isEmpty()) yield 0.05;
                boolean addsEvidence = results.stream().anyMatch(p -> !previouslySeen.contains(p.id()));
                yield addsEvidence ? 0.92 : 0.18;
            }
            case "get_history" -> hasHistory ? 0.10 : 0.62;
            case "check_stock" -> {
                String id = productId(proposal.args());
                boolean appearedInSearch = observations.stream()
                        .filter(s -> s.startsWith("[search_catalog]"))
                        .anyMatch(s -> s.contains(id + "|"));
                boolean alreadyChecked = observations.stream().anyMatch(s -> s.startsWith("[check_stock] " + id + " stock="));
                yield !id.isBlank() && appearedInSearch && !alreadyChecked && catalog.byId(id).isPresent() ? 0.96 : 0.05;
            }
            case "finish" -> EvidenceVerifier.goalSatisfied(query, observations, catalog) ? 1.0 : 0.0;
            default -> 0.0;
        };
    }

    private static List<String> searchedProductIds(List<String> observations) {
        List<String> ids = new ArrayList<>();
        for (String observation : observations) {
            if (!observation.startsWith("[search_catalog]")) continue;
            Matcher matcher = PRODUCT_ID.matcher(observation);
            while (matcher.find()) if (!ids.contains(matcher.group())) ids.add(matcher.group());
        }
        return ids;
    }

    private static boolean alreadySearchedSameQuery(List<String> observations, String proposedQuery) {
        String expected = proposedQuery == null ? "" : proposedQuery.trim();
        if (expected.isEmpty()) return observations.stream().anyMatch(s -> s.startsWith("[search_catalog]"));
        for (String observation : observations) {
            if (!observation.startsWith("[search_catalog]")) continue;
            int open = observation.indexOf('"');
            int close = open < 0 ? -1 : observation.indexOf('"', open + 1);
            if (open >= 0 && close > open
                    && observation.substring(open + 1, close).trim().equalsIgnoreCase(expected)) return true;
        }
        return false;
    }

    private static String productId(String raw) {
        Matcher matcher = PRODUCT_ID.matcher(raw == null ? "" : raw);
        return matcher.find() ? matcher.group() : "";
    }
}
