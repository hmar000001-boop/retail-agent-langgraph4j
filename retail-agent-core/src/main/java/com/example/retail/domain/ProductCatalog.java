package com.example.retail.domain;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Comparator;

/** In-memory catalog (the system of record). The vector store only indexes its text. */
public class ProductCatalog {
    private final List<Product> products = List.of(
            new Product("p-101", "Trail Runner X", "footwear", "lightweight trail running shoes with aggressive grip for muddy mountain trails", 89.0, 12),
            new Product("p-102", "Road Glide 3", "footwear", "cushioned road running shoes for marathon training on asphalt", 129.0, 5),
            new Product("p-103", "Peak Trail GTX", "footwear", "waterproof trail running shoes with rock plate for technical trails", 149.0, 4),
            new Product("p-201", "Fit Tracker Pro", "wearables", "gps fitness tracker with heart rate for running and cycling", 99.0, 20),
            new Product("p-202", "Hydration Vest 5L", "accessories", "running hydration vest with soft flasks for long trail runs", 59.0, 8),
            new Product("p-301", "Merino Run Socks", "apparel", "cushioned merino wool running socks that prevent blisters", 18.0, 50),
            new Product("p-302", "Rain Shell Jacket", "apparel", "packable waterproof running jacket for rainy weather", 119.0, 7),
            new Product("p-401", "Yoga Mat Grip", "fitness", "non slip yoga mat for home workouts", 35.0, 15),
            new Product("p-402", "Adjustable Dumbbell Set", "fitness", "adjustable dumbbells for strength training at home", 199.0, 3),
            new Product("p-501", "Compact Espresso Maker", "kitchen", "compact espresso machine for small kitchens", 149.0, 6));

    public List<Product> all() {
        return products;
    }

    public Optional<Product> byId(String id) {
        return products.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    /** Small deterministic lexical search used by the LangChain4j adapter (not a semantic-search claim). */
    public List<Product> search(String query, int limit) {
        if (limit <= 0) return List.of();
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        Matcher price = Pattern.compile("(?i)(?:under|below|less than|up to|maximum|max(?:imum)?(?: of)?)\\s*\\$?\\s*(\\d+(?:\\.\\d{1,2})?)").matcher(normalized);
        double maxPrice = price.find() ? Double.parseDouble(price.group(1)) : Double.POSITIVE_INFINITY;
        Set<String> terms = java.util.Arrays.stream(normalized.replaceAll("[^a-z0-9 ]", " ").split("\\s+"))
                .map(ProductCatalog::normalizeTerm).filter(t -> t.length() > 2)
                .filter(t -> !Set.of("the", "and", "for", "with", "under", "below", "less", "than", "maximum", "max", "need", "want", "find", "item", "items").contains(t))
                .collect(java.util.stream.Collectors.toSet());
        return products.stream()
                .filter(p -> p.price() <= maxPrice)
                .map(p -> java.util.Map.entry(p, relevance(terms, p)))
                .filter(e -> terms.isEmpty() || e.getValue() > 0)
                .sorted(Comparator.<java.util.Map.Entry<Product, Integer>>comparingInt(java.util.Map.Entry::getValue)
                        .reversed().thenComparing(e -> e.getKey().id()))
                .limit(limit).map(java.util.Map.Entry::getKey).toList();
    }

    private static int relevance(Set<String> terms, Product product) {
        String text = normalizeText(product.name() + " " + product.category() + " " + product.description());
        int score = 0;
        for (String term : terms) if (text.contains(term)) score += 1;
        return score;
    }

    private static String normalizeText(String text) {
        return java.util.Arrays.stream(text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").split("\\s+"))
                .map(ProductCatalog::normalizeTerm).collect(java.util.stream.Collectors.joining(" "));
    }

    private static String normalizeTerm(String term) {
        if (term.endsWith("ies") && term.length() > 4) return term.substring(0, term.length() - 3) + "y";
        if (term.endsWith("s") && term.length() > 3) return term.substring(0, term.length() - 1);
        return term;
    }
}
