package com.example.retail.domain;

import java.util.List;
import java.util.Map;

public class OrderHistory {
    private final Map<String, List<String>> purchases = Map.of(
            "c-100", List.of("p-102", "p-201"),
            "c-200", List.of("p-401"));

    public List<String> productIds(String customerId) {
        return purchases.getOrDefault(customerId, List.of());
    }
}
