package com.example.retail.domain;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-process episodic memory. This is a teaching/demo store, not durable production storage. */
public class EpisodicMemory {
    private final Map<String, CopyOnWriteArrayList<String>> notes = new ConcurrentHashMap<>();

    public List<String> recent(String customerId, int n) {
        List<String> all = notes.getOrDefault(customerId, new CopyOnWriteArrayList<>());
        return List.copyOf(all.subList(Math.max(0, all.size() - n), all.size()));
    }

    public void remember(String customerId, String note) {
        notes.computeIfAbsent(customerId, ignored -> new CopyOnWriteArrayList<>()).add(note);
    }
}
