package com.example.retail.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** CoALA episodic memory: what happened in earlier sessions with this customer. Written by the learn node. */
@Component
public class EpisodicMemory {
    private final Map<String, List<String>> notes = new ConcurrentHashMap<>();

    public List<String> recent(String customerId, int n) {
        List<String> all = notes.getOrDefault(customerId, List.of());
        return all.subList(Math.max(0, all.size() - n), all.size());
    }

    public void remember(String customerId, String note) {
        notes.computeIfAbsent(customerId, k -> new ArrayList<>()).add(note);
    }
}
