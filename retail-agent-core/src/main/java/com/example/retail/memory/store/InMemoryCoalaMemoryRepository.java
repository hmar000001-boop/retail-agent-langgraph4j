package com.example.retail.memory.store;

import com.example.retail.memory.domain.*;
import com.example.retail.memory.port.CoalaMemoryRepository;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Deterministic test/local adapter. Never selected by the production profile. */
public final class InMemoryCoalaMemoryRepository implements CoalaMemoryRepository {
    private final Map<Key, EpisodicMemoryRecord> episodes = new ConcurrentHashMap<>();
    private final Map<Key, SemanticMemoryRecord> facts = new ConcurrentHashMap<>();
    private final Map<Key, ProceduralMemoryRecord> procedures = new ConcurrentHashMap<>();
    private final Map<Key, WorkingMemorySnapshot> working = new ConcurrentHashMap<>();
    private final Map<Key, MemoryUseRecord> uses = new ConcurrentHashMap<>();
    private final Map<Key, MemoryAttributionRecord> attributionsByUse = new ConcurrentHashMap<>();

    @Override public void append(EpisodicMemoryRecord e) {
        Key k = key(e.subjectId(), e.id());
        EpisodicMemoryRecord prior = episodes.putIfAbsent(k, e);
        if (prior != null && !prior.sameLogicalEvent(e))
            throw new IllegalStateException("episodic memory ID collision");
    }
    @Override public void save(EpisodicMemoryRecord e) { episodes.put(key(e.subjectId(), e.id()), e); }
    @Override public synchronized void update(EpisodicMemoryRecord e, Instant expectedUpdatedAt) {
        Key k=key(e.subjectId(),e.id()); EpisodicMemoryRecord current=episodes.get(k);
        if (current==null || !current.updatedAt().equals(expectedUpdatedAt)) throw new ConcurrentModificationException("episodic memory revision conflict");
        episodes.put(k,e);
    }
    @Override public Optional<EpisodicMemoryRecord> findEpisodeById(String s, String id) { return Optional.ofNullable(episodes.get(key(s,id))); }
    @Override public List<EpisodicMemoryRecord> findRecent(String s, Instant at, int limit) {
        positive(limit);
        return episodes.values().stream().filter(e -> e.subjectId().equals(s) && e.retrievableAt(at))
                .sorted(Comparator.comparing(EpisodicMemoryRecord::occurredAt).reversed())
                .limit(limit).toList();
    }
    @Override public void append(SemanticMemoryRecord f) {
        Key k = key(f.subjectId(), f.id());
        SemanticMemoryRecord prior = facts.putIfAbsent(k, f);
        if (prior != null && !prior.sameLogicalFact(f)) throw new IllegalStateException("semantic memory ID collision");
    }
    @Override public void save(SemanticMemoryRecord f) { facts.put(key(f.subjectId(), f.id()), f); }
    @Override public synchronized void update(SemanticMemoryRecord f, Instant expectedUpdatedAt) {
        Key k=key(f.subjectId(),f.id()); SemanticMemoryRecord current=facts.get(k);
        if (current==null || !current.updatedAt().equals(expectedUpdatedAt)) throw new ConcurrentModificationException("semantic memory revision conflict");
        facts.put(k,f);
    }
    @Override public Optional<SemanticMemoryRecord> findSemanticById(String s, String id) { return Optional.ofNullable(facts.get(key(s,id))); }
    @Override public List<SemanticMemoryRecord> search(String s, String query, Instant at, int limit) {
        positive(limit); String q = required(query, "query").toLowerCase(Locale.ROOT);
        return facts.values().stream().filter(f -> f.subjectId().equals(s) && f.retrievableAt(at))
                .filter(f -> (f.subject()+" "+f.predicate()+" "+f.value()).toLowerCase(Locale.ROOT).contains(q))
                .sorted(Comparator.comparingDouble(SemanticMemoryRecord::confidence).reversed())
                .limit(limit).toList();
    }
    @Override public synchronized void replace(String s, String oldId, SemanticMemoryRecord replacement, Instant at) {
        Key oldKey = key(s, oldId);
        SemanticMemoryRecord old = facts.get(oldKey);
        if (old == null) throw new NoSuchElementException("semantic fact not found in subject scope");
        if (replacement.id().equals(oldId)) throw new IllegalArgumentException("replacement must have a distinct id");
        if (!replacement.subjectId().equals(s) || !Objects.equals(replacement.supersedesId(), oldId))
            throw new IllegalArgumentException("replacement must be subject-scoped and reference the superseded fact");
        if (replacement.lifecycle() != MemoryLifecycle.CANDIDATE && replacement.lifecycle() != MemoryLifecycle.ACTIVE)
            throw new IllegalArgumentException("replacement must begin as candidate or active");
        Key replacementKey = key(s, replacement.id());
        SemanticMemoryRecord existingReplacement = facts.get(replacementKey);
        if (existingReplacement != null) {
            if (old.lifecycle() == MemoryLifecycle.SUPERSEDED && existingReplacement.sameStoredVersion(replacement)) return;
            throw new IllegalStateException("replacement semantic memory ID already exists");
        }
        facts.put(oldKey, old.withLifecycle(MemoryLifecycle.SUPERSEDED, at));
        facts.put(replacementKey, replacement);
    }
    @Override public void append(ProceduralMemoryRecord p) {
        Key k = key(p.subjectId(), p.id());
        ProceduralMemoryRecord prior = procedures.putIfAbsent(k, p);
        if (prior != null && !prior.sameMethodDefinition(p)) throw new IllegalStateException("procedural memory ID collision");
    }
    @Override public void save(ProceduralMemoryRecord p) { procedures.put(key(p.subjectId(),p.id()),p); }
    @Override public synchronized void update(ProceduralMemoryRecord p, Instant expectedUpdatedAt) {
        Key k=key(p.subjectId(),p.id()); ProceduralMemoryRecord current=procedures.get(k);
        if (current==null || !current.updatedAt().equals(expectedUpdatedAt)) throw new ConcurrentModificationException("procedural memory revision conflict");
        procedures.put(k,p);
    }
    @Override public Optional<ProceduralMemoryRecord> findProcedureById(String s,String id) { return Optional.ofNullable(procedures.get(key(s,id))); }
    @Override public List<ProceduralMemoryRecord> findApplicable(String s,List<String> tags,int limit) {
        positive(limit); List<String> safeTags=tags==null?List.of():List.copyOf(tags);
        return procedures.values().stream().filter(p->p.subjectId().equals(s)&&p.applicableTo(safeTags))
                .sorted(Comparator.comparingInt(ProceduralMemoryRecord::successfulValidations).reversed())
                .limit(limit).toList();
    }
    @Override public Optional<WorkingMemorySnapshot> find(String s,String execution) { return Optional.ofNullable(working.get(key(s,execution))); }
    @Override public synchronized WorkingMemorySnapshot save(WorkingMemorySnapshot snapshot,long expectedRevision) {
        Key k=key(snapshot.subjectId(),snapshot.executionId()); WorkingMemorySnapshot previous=working.get(k);
        if (expectedRevision == -1) {
            if (previous != null) throw new ConcurrentModificationException("working memory already exists");
            if (snapshot.revision()!=0) throw new IllegalArgumentException("new working memory must start at revision 0");
        } else {
            if (previous == null || previous.revision()!=expectedRevision) throw new ConcurrentModificationException("working memory revision conflict");
            if (snapshot.revision()!=expectedRevision+1) throw new IllegalArgumentException("revision must advance exactly once");
        }
        working.put(k,snapshot); return snapshot;
    }
    @Override public void recordUse(MemoryUseRecord use) {
        MemoryUseRecord prior=uses.putIfAbsent(key(use.subjectId(),use.id()),use);
        if (prior!=null && !prior.sameLogicalUse(use)) throw new IllegalStateException("memory-use id collision");
    }
    @Override public Optional<MemoryUseRecord> findUse(String s,String id) { return Optional.ofNullable(uses.get(key(s,id))); }
    @Override public synchronized void recordAttribution(MemoryAttributionRecord attribution) {
        Key k=key(attribution.subjectId(),attribution.useId());
        boolean idCollision = attributionsByUse.values().stream().anyMatch(existing ->
                existing.subjectId().equals(attribution.subjectId()) && existing.id().equals(attribution.id())
                        && !existing.useId().equals(attribution.useId()));
        if (idCollision) throw new IllegalStateException("memory-attribution id collision");
        MemoryAttributionRecord prior=attributionsByUse.putIfAbsent(k,attribution);
        if (prior!=null && !prior.sameLogicalAttribution(attribution))
            throw new IllegalStateException("a memory use can be attributed only once");
    }
    @Override public Optional<MemoryAttributionRecord> findAttributionByUse(String s,String useId) {
        return Optional.ofNullable(attributionsByUse.get(key(s,useId)));
    }
    private static Key key(String subject,String id) { return new Key(required(subject,"subjectId"),required(id,"id")); }
    private static String required(String s,String n) { if(s==null||s.isBlank()) throw new IllegalArgumentException(n+" must not be blank"); return s.trim(); }
    private static void positive(int n) { if(n<1) throw new IllegalArgumentException("limit must be positive"); }
    private record Key(String subject,String id) { }
}
