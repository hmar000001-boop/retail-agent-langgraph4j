package com.example.retail.core;

import com.example.retail.domain.Product;
import com.example.retail.domain.ProductCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic evidence checker. It never trusts model confidence or a model's goalSatisfied claim.
 * The rules are intentionally small and inspectable for this retail teaching scenario.
 */
public final class EvidenceVerifier {
    private static final Pattern STOCK = Pattern.compile("\\[check_stock]\\s+(p-\\d+)\\s+stock=(\\d+)\\s+price=([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern PRICE_LIMIT = Pattern.compile("(?i)(?:under|below|less than|up to|maximum|max(?:imum)?(?: of)?)\\s*\\$?\\s*(\\d+(?:\\.\\d{1,2})?)");

    private EvidenceVerifier() {}

    public static List<Product> verifiedProducts(String query, List<String> observations, ProductCatalog catalog) {
        List<Product> result = new ArrayList<>();
        double limit = priceLimit(query);
        for (String observation : observations) {
            Matcher matcher = STOCK.matcher(observation);
            if (!matcher.find()) continue;
            String id = matcher.group(1);
            int observedStock = Integer.parseInt(matcher.group(2));
            double observedPrice = Double.parseDouble(matcher.group(3));
            catalog.byId(id).ifPresent(product -> {
                boolean current = product.stock() == observedStock
                        && Math.abs(product.price() - observedPrice) < 0.001;
                if (current && observedStock > 0 && product.price() <= limit && matchesRequest(query, product)) {
                    if (!result.contains(product)) result.add(product);
                }
            });
        }
        return List.copyOf(result);
    }

    public static boolean goalSatisfied(String query, List<String> observations, ProductCatalog catalog) {
        return !verifiedProducts(query, observations, catalog).isEmpty();
    }

    private static boolean matchesRequest(String query, Product product) {
        String q = query.toLowerCase(Locale.ROOT);
        String searchable = (product.name() + " " + product.category() + " " + product.description()).toLowerCase(Locale.ROOT);
        if ((q.contains("waterproof") || q.contains("gore-tex") || q.contains(" gtx"))
                && !(searchable.contains("waterproof") || searchable.contains("gore-tex") || searchable.contains("gtx"))) return false;
        if ((q.contains("trail") || q.contains("rocky")) && !searchable.contains("trail")) return false;
        if (q.contains("running") && !searchable.contains("running") && !searchable.contains("run")) return false;
        if (q.contains("shoe") && !product.category().equalsIgnoreCase("footwear")) return false;
        return true;
    }

    public static double priceLimit(String query) {
        Matcher matcher = PRICE_LIMIT.matcher(query);
        if (matcher.find()) return Double.parseDouble(matcher.group(1));
        return Double.POSITIVE_INFINITY;
    }
}
