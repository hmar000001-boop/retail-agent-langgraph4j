package com.example.retail.memory.service;

import com.example.retail.memory.domain.*;
import com.example.retail.memory.port.CoalaMemoryRepository;
import com.example.retail.agent.domain.Evidence;
import com.example.retail.agent.port.EvidenceRepository;
import com.example.retail.memory.store.InMemoryEvidenceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Application boundary for memory lifecycle, scope checks and evidence-aware attribution. */
public final class CoalaMemoryService {
    private final CoalaMemoryRepository repository;
    private final EvidenceRepository evidenceRepository;
    private final int procedureActivationThreshold;

    /** Local/test convenience constructor. Production wiring must inject a durable EvidenceRepository. */
    public CoalaMemoryService(CoalaMemoryRepository repository) {
        this(repository, new InMemoryEvidenceRepository(), 2);
    }
    public CoalaMemoryService(CoalaMemoryRepository repository, EvidenceRepository evidenceRepository) {
        this(repository, evidenceRepository, 2);
    }
    public CoalaMemoryService(CoalaMemoryRepository repository, EvidenceRepository evidenceRepository, int procedureActivationThreshold) {
        this.repository=Objects.requireNonNull(repository,"repository");
        this.evidenceRepository=Objects.requireNonNull(evidenceRepository,"evidenceRepository");
        if(procedureActivationThreshold<2) throw new IllegalArgumentException("procedure activation threshold cannot be below two");
        this.procedureActivationThreshold=procedureActivationThreshold;
    }

    public void recordEvidence(Evidence evidence,String subjectId) {
        evidenceRepository.save(Objects.requireNonNull(evidence,"evidence"),subjectId);
    }
    public void recordEpisode(EpisodicMemoryRecord episode) {
        Objects.requireNonNull(episode,"episode");
        Optional<EpisodicMemoryRecord> prior = repository.findEpisodeById(episode.subjectId(),episode.id());
        if (prior.isPresent()) {
            if (prior.get().sameLogicalEvent(episode)) return;
            throw new IllegalStateException("episodic memory ID collision");
        }
        if (episode.outcome()==EpisodicMemoryRecord.Outcome.VERIFIED_SUCCESS
                || episode.outcome()==EpisodicMemoryRecord.Outcome.VERIFIED_FAILURE
                || episode.outcome()==EpisodicMemoryRecord.Outcome.PARTIAL) {
            requireOutcomeEvidence(episode.subjectId(),episode.evidenceIds(),episode.occurredAt(),"verified episodic outcome");
        } else if (!episode.evidenceIds().isEmpty()) {
            requireVerifiedEvidence(episode.subjectId(),episode.evidenceIds(),episode.occurredAt(),"episodic evidence link");
        }
        repository.append(episode);
    }
    public EpisodicMemoryRecord transitionEpisode(String subjectId, String episodeId, MemoryLifecycle next, Instant at) {
        EpisodicMemoryRecord current = repository.findEpisodeById(subjectId, episodeId)
                .orElseThrow(() -> new IllegalArgumentException("episode not found in subject scope"));
        EpisodicMemoryRecord updated = current.withLifecycle(next,monotonicTimestamp(at,current.updatedAt()));
        repository.update(updated,current.updatedAt());
        return updated;
    }
    public List<EpisodicMemoryRecord> recentEpisodes(String subjectId,Instant at,int limit) { return repository.findRecent(subjectId,at,limit); }
    public Optional<EpisodicMemoryRecord> episode(String subjectId,String id) { return repository.findEpisodeById(subjectId,id); }

    public void proposeSemanticFact(SemanticMemoryRecord fact) {
        if(fact.lifecycle()!=MemoryLifecycle.CANDIDATE) throw new IllegalArgumentException("new semantic facts must enter as CANDIDATE");
        repository.append(fact);
    }
    public SemanticMemoryRecord activateSemanticFact(String subjectId,String factId,List<String> evidenceIds,Instant at) {
        SemanticMemoryRecord fact=repository.findSemanticById(subjectId,factId).orElseThrow(()->new IllegalArgumentException("semantic fact not found in subject scope"));
        List<String> requestedEvidence = evidenceIds == null ? List.of() : evidenceIds.stream().distinct().toList();
        if (fact.lifecycle()==MemoryLifecycle.ACTIVE && fact.evidenceIds().equals(requestedEvidence)) return fact;
        if(fact.lifecycle()!=MemoryLifecycle.CANDIDATE) throw new IllegalStateException("only candidate semantic facts can be activated");
        requireVerifiedEvidence(subjectId,evidenceIds,at,"semantic fact activation");
        SemanticMemoryRecord active=new SemanticMemoryRecord(fact.id(),fact.subjectId(),fact.subject(),fact.predicate(),fact.value(),
                fact.confidence(),evidenceIds,fact.provenance(),MemoryLifecycle.ACTIVE,fact.validFrom(),fact.validUntil(),
                fact.supersedesId(),fact.createdAt(),monotonicTimestamp(at,fact.updatedAt()));
        repository.update(active,fact.updatedAt()); return active;
    }
    public List<SemanticMemoryRecord> searchSemantic(String subjectId,String query,Instant at,int limit) { return repository.search(subjectId,query,at,limit); }
    public void replaceSemanticFact(String subjectId,String oldId,SemanticMemoryRecord replacement,Instant at) {
        Objects.requireNonNull(replacement,"replacement");
        if (!replacement.subjectId().equals(subjectId) || !Objects.equals(replacement.supersedesId(),oldId))
            throw new IllegalArgumentException("replacement must be in the same subject scope and reference the old fact");
        if (replacement.lifecycle()!=MemoryLifecycle.CANDIDATE && replacement.lifecycle()!=MemoryLifecycle.ACTIVE)
            throw new IllegalArgumentException("replacement must begin as CANDIDATE or ACTIVE");
        SemanticMemoryRecord old = repository.findSemanticById(subjectId,oldId)
                .orElseThrow(()->new IllegalArgumentException("semantic fact not found in subject scope"));
        Optional<SemanticMemoryRecord> priorReplacement = repository.findSemanticById(subjectId,replacement.id());
        if (old.lifecycle()==MemoryLifecycle.SUPERSEDED && priorReplacement.isPresent()
                && priorReplacement.get().sameStoredVersion(replacement)) return;
        if (replacement.lifecycle()==MemoryLifecycle.ACTIVE)
            requireVerifiedEvidence(subjectId,replacement.evidenceIds(),at,"semantic fact replacement");
        repository.replace(subjectId,oldId,replacement,monotonicTimestamp(at,old.updatedAt()));
    }

    public void proposeProcedure(ProceduralMemoryRecord procedure) {
        if(procedure.lifecycle()!=MemoryLifecycle.CANDIDATE) throw new IllegalArgumentException("new procedures must enter as CANDIDATE");
        repository.append(procedure);
    }
    public ProceduralMemoryRecord validateProcedure(String subjectId,String procedureId,boolean success,String evidenceId,Instant at) {
        for (int attempt=0; attempt<3; attempt++) {
            ProceduralMemoryRecord current=repository.findProcedureById(subjectId,procedureId)
                    .orElseThrow(()->new IllegalArgumentException("procedure not found in subject scope"));
            if (success && current.successfulEvidenceIds().contains(evidenceId)) return current;
            if (!success && current.failedEvidenceIds().contains(evidenceId)) return current;
            if ((success && current.failedEvidenceIds().contains(evidenceId))
                    || (!success && current.successfulEvidenceIds().contains(evidenceId)))
                throw new IllegalArgumentException("one evidence event cannot validate both success and failure");
            if(current.lifecycle()!=MemoryLifecycle.CANDIDATE && current.lifecycle()!=MemoryLifecycle.ACTIVE)
                throw new IllegalStateException("only candidate or active procedures can be validated");
            requireOutcomeEvidence(subjectId,List.of(evidenceId),at,"procedural validation");
            ProceduralMemoryRecord updated=current.recordValidation(success,evidenceId,
                    monotonicTimestamp(at,current.updatedAt()),procedureActivationThreshold);
            try {
                repository.update(updated,current.updatedAt());
                return updated;
            } catch (java.util.ConcurrentModificationException conflict) {
                if (attempt==2) throw conflict;
            }
        }
        throw new IllegalStateException("procedural validation retry loop exhausted");
    }
    public List<ProceduralMemoryRecord> applicableProcedures(String subjectId,List<String> tags,int limit) {
        return repository.findApplicable(subjectId,tags,limit);
    }

    public WorkingMemorySnapshot createWorkingMemory(String subjectId,String executionId,java.util.Map<String,String> values,Instant at,Instant expiresAt) {
        return repository.save(new WorkingMemorySnapshot(subjectId,executionId,0,values,at,expiresAt),-1);
    }
    public WorkingMemorySnapshot updateWorkingMemory(WorkingMemorySnapshot current,java.util.Map<String,String> values,Instant at,Instant expiresAt) {
        Objects.requireNonNull(current,"current");
        WorkingMemorySnapshot next=current.next(values,at,expiresAt);
        return repository.save(next,current.revision());
    }
    public Optional<WorkingMemorySnapshot> workingMemory(String subjectId,String executionId,Instant at) {
        return repository.find(subjectId,executionId).filter(snapshot->!snapshot.isExpiredAt(at));
    }

    public void recordUse(MemoryUseRecord use) {
        Objects.requireNonNull(use,"use");
        Optional<MemoryUseRecord> prior = repository.findUse(use.subjectId(),use.id());
        if (prior.isPresent()) {
            if (prior.get().sameLogicalUse(use)) return;
            throw new IllegalStateException("memory-use id collision");
        }
        if (!memoryUsableAt(use.subjectId(), use.memoryId(), use.memoryKind(), use.occurredAt()))
            throw new IllegalArgumentException("memory use must reference an active, in-scope memory at the recorded time");
        repository.recordUse(use);
    }
    /** A task outcome alone is insufficient: the caller must supply a use-specific relation and evidence. */
    public void attributeUse(MemoryAttributionRecord attribution) {
        Optional<MemoryAttributionRecord> prior = repository.findAttributionByUse(attribution.subjectId(),attribution.useId());
        if (prior.isPresent()) {
            if (prior.get().sameLogicalAttribution(attribution)) return;
            throw new IllegalStateException("a memory use can be attributed only once");
        }
        MemoryUseRecord use=repository.findUse(attribution.subjectId(),attribution.useId())
                .orElseThrow(()->new IllegalArgumentException("memory use does not exist in subject scope"));
        if(!use.subjectId().equals(attribution.subjectId()) || !use.executionId().equals(attribution.executionId()))
            throw new SecurityException("memory attribution subject/execution mismatch");
        if (!memoryExists(use.subjectId(), use.memoryId(), use.memoryKind()))
            throw new IllegalArgumentException("attributed memory no longer exists in subject scope");
        if(attribution.relation()==MemoryAttributionRecord.Relation.SUPPORTED
                || attribution.relation()==MemoryAttributionRecord.Relation.CONTRADICTED) {
            requireOutcomeEvidence(attribution.subjectId(),attribution.evidenceIds(),attribution.attributedAt(),"memory outcome attribution");
            if (use.stage()!=MemoryUseRecord.Stage.CITED_AS_EVIDENCE
                    && use.stage()!=MemoryUseRecord.Stage.USED_IN_PLAN
                    && use.stage()!=MemoryUseRecord.Stage.USED_IN_ACTION)
                throw new IllegalArgumentException("causal attribution requires a recorded evidence/plan/action use, not mere workspace exposure");
        }
        repository.recordAttribution(attribution);
    }
    public Optional<MemoryAttributionRecord> attribution(String subjectId,String useId) {
        return repository.findAttributionByUse(subjectId,useId);
    }

    /** Use a timestamp delta larger than PostgreSQL's microsecond precision for optimistic CAS versions. */
    private static Instant monotonicTimestamp(Instant requested, Instant previous) {
        Objects.requireNonNull(requested,"at");
        Instant minimum = previous.plusMillis(1);
        return requested.isAfter(minimum) ? requested : minimum;
    }

    private boolean memoryExists(String subjectId,String memoryId,MemoryUseRecord.Kind kind) {
        return switch (kind) {
            case EPISODIC -> repository.findEpisodeById(subjectId,memoryId).isPresent();
            case SEMANTIC -> repository.findSemanticById(subjectId,memoryId).isPresent();
            case PROCEDURAL -> repository.findProcedureById(subjectId,memoryId).isPresent();
        };
    }

    private void requireVerifiedEvidence(String subjectId,List<String> evidenceIds,Instant at,String operation) {
        if(evidenceIds==null||evidenceIds.isEmpty()) throw new IllegalArgumentException(operation+" requires evidence references");
        List<String> distinct=evidenceIds.stream().distinct().toList();
        List<Evidence> found=evidenceRepository.findByIds(distinct,subjectId);
        if(found.size()!=distinct.size()) throw new IllegalArgumentException(operation+" references missing/out-of-scope evidence");
        for(Evidence evidence:found) {
            if(!evidence.isFreshAt(at)) throw new IllegalArgumentException(operation+" references stale or future evidence: "+evidence.id());
            if(evidence.authority()==Evidence.Authority.UNVERIFIED_SOURCE || evidence.authority()==Evidence.Authority.MODEL_GENERATED)
                throw new IllegalArgumentException(operation+" cannot rely on unverified/model-generated evidence: "+evidence.id());
        }
    }

    private void requireOutcomeEvidence(String subjectId,List<String> evidenceIds,Instant at,String operation) {
        requireVerifiedEvidence(subjectId,evidenceIds,at,operation);
        List<Evidence> found=evidenceRepository.findByIds(evidenceIds.stream().distinct().toList(),subjectId);
        for (Evidence evidence : found) {
            if (evidence.authority()!=Evidence.Authority.SYSTEM_OF_RECORD
                    && evidence.authority()!=Evidence.Authority.VERIFIED_SOURCE)
                throw new IllegalArgumentException(operation+" requires system-of-record or verified-source evidence: "+evidence.id());
        }
    }

    private boolean memoryUsableAt(String subjectId,String memoryId,MemoryUseRecord.Kind kind,Instant at) {
        return switch (kind) {
            case EPISODIC -> repository.findEpisodeById(subjectId,memoryId).filter(e->e.retrievableAt(at)).isPresent();
            case SEMANTIC -> repository.findSemanticById(subjectId,memoryId).filter(f->f.retrievableAt(at)).isPresent();
            case PROCEDURAL -> repository.findProcedureById(subjectId,memoryId).filter(p->p.lifecycle()==MemoryLifecycle.ACTIVE).isPresent();
        };
    }
}
