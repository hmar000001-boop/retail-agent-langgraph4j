package com.example.retail.memory;

import com.example.retail.domain.EpisodicMemory;
import com.example.retail.memory.domain.*;
import com.example.retail.memory.port.CoalaMemoryRepository;
import com.example.retail.memory.store.InMemoryCoalaMemoryRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Compatibility adapter for existing tests and legacy constructors. The experimental LangChain4j
 * Agentic module continues using its original EpisodicMemory class unchanged.
 */
public final class LegacyEpisodicCoalaRepository implements CoalaMemoryRepository {
    private final EpisodicMemory legacy;
    private final InMemoryCoalaMemoryRepository delegate = new InMemoryCoalaMemoryRepository();
    public LegacyEpisodicCoalaRepository(EpisodicMemory legacy) { this.legacy=Objects.requireNonNull(legacy,"legacy"); }

    @Override public void append(EpisodicMemoryRecord episode) { delegate.append(episode); legacy.remember(episode.subjectId(),episode.summary()); }
    @Override public void save(EpisodicMemoryRecord episode) { delegate.save(episode); legacy.remember(episode.subjectId(),episode.summary()); }
    @Override public void update(EpisodicMemoryRecord episode,Instant expected) { delegate.update(episode,expected); }
    @Override public Optional<EpisodicMemoryRecord> findEpisodeById(String subjectId,String id) { return delegate.findEpisodeById(subjectId,id); }
    @Override public List<EpisodicMemoryRecord> findRecent(String subjectId,Instant at,int limit) {
        Map<String,EpisodicMemoryRecord> merged=new LinkedHashMap<>();
        delegate.findRecent(subjectId,at,limit).forEach(e->merged.put(e.id(),e));
        for(String note:legacy.recent(subjectId,Math.max(limit,20))) {
            if (merged.values().stream().anyMatch(existing -> existing.summary().equals(note))) continue;
            String id="legacy-"+UUID.nameUUIDFromBytes((subjectId+"\u0000"+note).getBytes(StandardCharsets.UTF_8));
            EpisodicMemoryRecord imported=new EpisodicMemoryRecord(id,subjectId,Instant.EPOCH,"legacy interaction",note,
                    EpisodicMemoryRecord.Outcome.UNKNOWN,List.of(),new MemoryProvenance(MemoryProvenance.Kind.OBSERVED_INTERACTION,
                    "legacy-episodic-memory","Imported from compatibility adapter",Instant.EPOCH),MemoryLifecycle.ACTIVE,
                    Instant.EPOCH,Instant.EPOCH,null);
            delegate.save(imported);
            merged.putIfAbsent(id,imported);
        }
        return merged.values().stream().sorted(Comparator.comparing(EpisodicMemoryRecord::occurredAt).reversed()).limit(limit).toList();
    }
    @Override public void append(SemanticMemoryRecord fact){delegate.append(fact);}
    @Override public void save(SemanticMemoryRecord fact){delegate.save(fact);}
    @Override public void update(SemanticMemoryRecord fact,Instant expected){delegate.update(fact,expected);}
    @Override public Optional<SemanticMemoryRecord> findSemanticById(String s,String id){return delegate.findSemanticById(s,id);}
    @Override public List<SemanticMemoryRecord> search(String s,String q,Instant at,int limit){return delegate.search(s,q,at,limit);}
    @Override public void replace(String s,String id,SemanticMemoryRecord r,Instant at){delegate.replace(s,id,r,at);}
    @Override public void append(ProceduralMemoryRecord p){delegate.append(p);}
    @Override public void save(ProceduralMemoryRecord p){delegate.save(p);}
    @Override public void update(ProceduralMemoryRecord p,Instant expected){delegate.update(p,expected);}
    @Override public Optional<ProceduralMemoryRecord> findProcedureById(String s,String id){return delegate.findProcedureById(s,id);}
    @Override public List<ProceduralMemoryRecord> findApplicable(String s,List<String> tags,int limit){return delegate.findApplicable(s,tags,limit);}
    @Override public Optional<WorkingMemorySnapshot> find(String s,String execution){return delegate.find(s,execution);}
    @Override public WorkingMemorySnapshot save(WorkingMemorySnapshot s,long expected){return delegate.save(s,expected);}
    @Override public void recordUse(MemoryUseRecord u){delegate.recordUse(u);}
    @Override public Optional<MemoryUseRecord> findUse(String s,String id){return delegate.findUse(s,id);}
    @Override public void recordAttribution(MemoryAttributionRecord a){delegate.recordAttribution(a);}
    @Override public Optional<MemoryAttributionRecord> findAttributionByUse(String s,String id){return delegate.findAttributionByUse(s,id);}
}
