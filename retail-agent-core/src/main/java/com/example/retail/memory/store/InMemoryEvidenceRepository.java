package com.example.retail.memory.store;

import com.example.retail.agent.domain.Evidence;
import com.example.retail.agent.port.EvidenceRepository;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Subject-scoped deterministic evidence adapter for tests and local execution. */
public final class InMemoryEvidenceRepository implements EvidenceRepository {
    private final Map<Key,Evidence> values=new ConcurrentHashMap<>();
    @Override public Optional<Evidence> findById(String evidenceId,String subjectId) {
        return Optional.ofNullable(values.get(new Key(required(subjectId,"subjectId"),required(evidenceId,"evidenceId"))));
    }
    @Override public List<Evidence> findByIds(List<String> evidenceIds,String subjectId) {
        if(evidenceIds==null||evidenceIds.isEmpty()) return List.of();
        return evidenceIds.stream().distinct().map(id->findById(id,subjectId)).flatMap(Optional::stream).toList();
    }
    @Override public void save(Evidence evidence,String subjectId) {
        Objects.requireNonNull(evidence,"evidence");
        Key key = new Key(required(subjectId,"subjectId"), evidence.id());
        Evidence prior = values.putIfAbsent(key, evidence);
        if (prior != null && !prior.sameAssertion(evidence))
            throw new IllegalStateException("evidence IDs are immutable within a subject scope");
    }
    private static String required(String value,String name){if(value==null||value.isBlank())throw new IllegalArgumentException(name+" must not be blank");return value.trim();}
    private record Key(String subjectId,String evidenceId) { }
}
