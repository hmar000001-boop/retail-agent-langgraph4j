package com.example.retail.memory;

import static org.assertj.core.api.Assertions.*;

import com.example.retail.memory.domain.*;
import com.example.retail.memory.service.CoalaMemoryService;
import com.example.retail.memory.store.InMemoryCoalaMemoryRepository;
import com.example.retail.memory.store.InMemoryEvidenceRepository;
import com.example.retail.agent.domain.Evidence;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.ConcurrentModificationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CoalaMemoryTest {
    private static final Instant NOW=Instant.parse("2026-11-06T12:00:00Z");
    private CoalaMemoryService memory;
    private InMemoryCoalaMemoryRepository repository;
    private InMemoryEvidenceRepository evidence;

    @BeforeEach void setup() { repository=new InMemoryCoalaMemoryRepository(); evidence=new InMemoryEvidenceRepository(); memory=new CoalaMemoryService(repository,evidence); }

    @Test void episodicRecallIsSubjectScopedAndLifecycleAndRetentionAware() {
        memory.recordEpisode(episode("ep-1","alice",MemoryLifecycle.ACTIVE,NOW.plusSeconds(100)));
        memory.recordEpisode(episode("ep-2","alice",MemoryLifecycle.CANDIDATE,NOW.plusSeconds(100)));
        memory.recordEpisode(episode("ep-3","bob",MemoryLifecycle.ACTIVE,NOW.plusSeconds(100)));
        memory.recordEpisode(episode("ep-4","alice",MemoryLifecycle.ACTIVE,NOW.minusSeconds(1)));

        assertThat(memory.recentEpisodes("alice",NOW,20)).extracting(EpisodicMemoryRecord::id).containsExactly("ep-1");
        assertThat(memory.episode("bob","ep-1")).isEmpty();
    }

    @Test void evidenceReplayKeepsFirstObservationTimestamp() {
        recordEvidence("alice","stable-evidence",Evidence.Authority.USER_DECLARED);
        Evidence replay = new Evidence("stable-evidence","profile","claim","value",Evidence.EvidenceSource.USER,
                Evidence.Authority.USER_DECLARED,"profile",NOW.plusSeconds(30),NOW.plusSeconds(90),"explicitly recorded test evidence");
        evidence.save(replay,"alice");
        assertThat(evidence.findById("stable-evidence","alice").orElseThrow().observedAt()).isEqualTo(NOW);
    }

    @Test void evidenceIdsAreImmutableWithinOwnerScope() {
        recordEvidence("alice","stable-evidence",Evidence.Authority.USER_DECLARED);
        Evidence changed = new Evidence("stable-evidence","profile","claim","different value",
                Evidence.EvidenceSource.USER,Evidence.Authority.USER_DECLARED,"profile",NOW,null,"changed assertion");
        assertThatThrownBy(()->evidence.save(changed,"alice")).isInstanceOf(IllegalStateException.class);
        assertThat(evidence.findById("stable-evidence","alice").orElseThrow().value()).isEqualTo("value");
    }

    @Test void replayedEpisodeEventIsIdempotentButConflictingIdReuseIsRejected() {
        EpisodicMemoryRecord original = episode("event-1","alice",MemoryLifecycle.ACTIVE,NOW.plusSeconds(90));
        memory.recordEpisode(original);
        EpisodicMemoryRecord replay = new EpisodicMemoryRecord(original.id(),original.subjectId(),NOW.plusSeconds(3),
                original.goal(),original.summary(),original.outcome(),original.evidenceIds(),
                new MemoryProvenance(original.provenance().kind(),original.provenance().sourceId(),original.provenance().description(),NOW.plusSeconds(3)),
                original.lifecycle(),NOW.plusSeconds(3),NOW.plusSeconds(3),NOW.plusSeconds(93));
        memory.recordEpisode(replay);
        assertThat(memory.episode("alice","event-1")).contains(original);
        EpisodicMemoryRecord conflict = new EpisodicMemoryRecord(original.id(),original.subjectId(),NOW,
                original.goal(),"different summary",original.outcome(),original.evidenceIds(),original.provenance(),
                original.lifecycle(),NOW,NOW,original.retentionUntil());
        assertThatThrownBy(()->memory.recordEpisode(conflict)).isInstanceOf(IllegalStateException.class);
    }

    @Test void verifiedEpisodeOutcomeRequiresPersistedFreshEvidence() {
        EpisodicMemoryRecord verified = new EpisodicMemoryRecord("verified-episode","alice",NOW,"find item","verified result",
                EpisodicMemoryRecord.Outcome.VERIFIED_SUCCESS,List.of("episode-evidence"),provenance(),
                MemoryLifecycle.ACTIVE,NOW,NOW,NOW.plusSeconds(60));
        assertThatThrownBy(()->memory.recordEpisode(verified)).isInstanceOf(IllegalArgumentException.class);
        recordEvidence("alice","episode-evidence",Evidence.Authority.SYSTEM_OF_RECORD);
        memory.recordEpisode(verified);
        assertThat(memory.recentEpisodes("alice",NOW,5)).containsExactly(verified);
    }

    @Test void semanticFactRequiresEvidenceBeforeActivationAndReplacementSupersedesOldFact() {
        SemanticMemoryRecord candidate=semantic("fact-1","alice",MemoryLifecycle.CANDIDATE,List.of(),null);
        memory.proposeSemanticFact(candidate);
        recordEvidence("alice","evidence-1",Evidence.Authority.USER_DECLARED);
        assertThatThrownBy(()->memory.activateSemanticFact("alice","fact-1",List.of(),NOW))
                .isInstanceOf(IllegalArgumentException.class);
        SemanticMemoryRecord active=memory.activateSemanticFact("alice","fact-1",List.of("evidence-1"),NOW);
        assertThat(active.lifecycle()).isEqualTo(MemoryLifecycle.ACTIVE);
        memory.proposeSemanticFact(candidate); // replay must not demote ACTIVE or erase evidence
        assertThat(memory.searchSemantic("alice","delivery",NOW,10)).containsExactly(active);

        recordEvidence("alice","evidence-2",Evidence.Authority.USER_DECLARED);
        SemanticMemoryRecord replacementBase=semantic("fact-2","alice",MemoryLifecycle.ACTIVE,List.of("evidence-2"),"fact-1");
        SemanticMemoryRecord replacement=new SemanticMemoryRecord(replacementBase.id(),replacementBase.subjectId(),
                replacementBase.subject(),replacementBase.predicate(),replacementBase.value(),replacementBase.confidence(),
                replacementBase.evidenceIds(),replacementBase.provenance(),replacementBase.lifecycle(),NOW.plusSeconds(60),
                replacementBase.validUntil(),replacementBase.supersedesId(),replacementBase.createdAt(),NOW.plusSeconds(60));
        memory.replaceSemanticFact("alice","fact-1",replacement,NOW.plusSeconds(60));
        memory.replaceSemanticFact("alice","fact-1",replacement,NOW.plusSeconds(90)); // exact retry is idempotent
        assertThat(memory.searchSemantic("alice","delivery",NOW.plusSeconds(30),10)).isEmpty();
        assertThat(memory.searchSemantic("alice","delivery",NOW.plusSeconds(60),10)).extracting(SemanticMemoryRecord::id).containsExactly("fact-2");
        assertThat(memory.searchSemantic("bob","delivery",NOW.plusSeconds(60),10)).isEmpty();
    }

    @Test void proceduralMemoryNeedsTwoDistinctSuccessfulEvidenceBackedValidations() {
        ProceduralMemoryRecord procedure=new ProceduralMemoryRecord("proc-1","alice","safe lookup","lookup then verify",
                List.of("catalog"),List.of("query is scoped"),List.of("search","verify"),List.of("verified result"),
                0,0,List.of(),List.of(),provenance(),MemoryLifecycle.CANDIDATE,NOW,NOW,null);
        memory.proposeProcedure(procedure);
        recordEvidence("alice","evidence-a",Evidence.Authority.SYSTEM_OF_RECORD);
        recordEvidence("alice","evidence-b",Evidence.Authority.SYSTEM_OF_RECORD);
        assertThat(memory.applicableProcedures("alice",List.of("catalog"),5)).isEmpty();
        assertThat(memory.validateProcedure("alice","proc-1",true,"evidence-a",NOW.plusSeconds(1)).lifecycle())
                .isEqualTo(MemoryLifecycle.CANDIDATE);
        assertThat(memory.validateProcedure("alice","proc-1",true,"evidence-a",NOW.plusSeconds(2)).successfulValidations())
                .isEqualTo(1);
        ProceduralMemoryRecord active=memory.validateProcedure("alice","proc-1",true,"evidence-b",NOW.plusSeconds(3));
        assertThat(active.lifecycle()).isEqualTo(MemoryLifecycle.ACTIVE);
        memory.proposeProcedure(procedure); // replay must not reset validation history or lifecycle
        assertThat(memory.applicableProcedures("alice",List.of("catalog"),5)).containsExactly(active);
        assertThat(memory.applicableProcedures("bob",List.of("catalog"),5)).isEmpty();
        recordEvidence("alice","evidence-failure",Evidence.Authority.SYSTEM_OF_RECORD);
        ProceduralMemoryRecord archived=memory.validateProcedure("alice","proc-1",false,"evidence-failure",NOW.plusSeconds(4));
        assertThat(archived.lifecycle()).isEqualTo(MemoryLifecycle.ARCHIVED);
        assertThat(memory.validateProcedure("alice","proc-1",false,"evidence-failure",NOW.plusSeconds(30))).isEqualTo(archived);
        assertThat(memory.applicableProcedures("alice",List.of("catalog"),5)).isEmpty();
    }

    @Test void workingMemoryUsesOptimisticRevisionAndExecutionScope() {
        WorkingMemorySnapshot created=memory.createWorkingMemory("alice","run-1",Map.of("query","shoes"),NOW,NOW.plusSeconds(60));
        WorkingMemorySnapshot updated=memory.updateWorkingMemory(created,Map.of("query","boots","step","verify"),NOW.plusSeconds(1),NOW.plusSeconds(60));
        assertThat(updated.revision()).isEqualTo(1);
        assertThat(memory.workingMemory("alice","run-1",NOW.plusSeconds(2))).contains(updated);
        assertThat(memory.workingMemory("bob","run-1",NOW.plusSeconds(2))).isEmpty();
        assertThatThrownBy(()->memory.updateWorkingMemory(created,Map.of(),NOW.plusSeconds(2),NOW.plusSeconds(60)))
                .isInstanceOf(ConcurrentModificationException.class);
        assertThat(memory.workingMemory("alice","run-1",NOW.plusSeconds(61))).isEmpty();
    }

    @Test void replayingTheSameMemoryUseIsIdempotentAcrossWallClockTimestamps() {
        memory.proposeSemanticFact(semantic("fact-1","alice",MemoryLifecycle.CANDIDATE,List.of(),null));
        recordEvidence("alice","evidence-1",Evidence.Authority.USER_DECLARED);
        memory.activateSemanticFact("alice","fact-1",List.of("evidence-1"),NOW);
        MemoryUseRecord first=new MemoryUseRecord("use-1","alice","run-1","fact-1",MemoryUseRecord.Kind.SEMANTIC,
                MemoryUseRecord.Stage.ADMITTED_TO_WORKSPACE,"planning-context",NOW);
        MemoryUseRecord replay=new MemoryUseRecord("use-1","alice","run-1","fact-1",MemoryUseRecord.Kind.SEMANTIC,
                MemoryUseRecord.Stage.ADMITTED_TO_WORKSPACE,"planning-context",NOW.plusSeconds(30));
        memory.recordUse(first);
        memory.recordUse(replay);
        assertThat(repository.findUse("alice","use-1")).contains(first);
        MemoryUseRecord collision=new MemoryUseRecord("use-1","alice","run-1","fact-1",MemoryUseRecord.Kind.SEMANTIC,
                MemoryUseRecord.Stage.USED_IN_PLAN,"planner",NOW.plusSeconds(31));
        assertThatThrownBy(()->memory.recordUse(collision)).isInstanceOf(IllegalStateException.class);
    }

    @Test void attributionIsPerUseEvidenceBackedAndCannotBeReapplied() {
        memory.proposeSemanticFact(semantic("fact-1","alice",MemoryLifecycle.CANDIDATE,List.of(),null));
        recordEvidence("alice","evidence-1",Evidence.Authority.USER_DECLARED);
        recordEvidence("alice","inventory-evidence-7",Evidence.Authority.SYSTEM_OF_RECORD);
        memory.activateSemanticFact("alice","fact-1",List.of("evidence-1"),NOW);
        MemoryUseRecord use=new MemoryUseRecord("use-1","alice","run-1","fact-1",MemoryUseRecord.Kind.SEMANTIC,
                MemoryUseRecord.Stage.CITED_AS_EVIDENCE,"proposal",NOW);
        memory.recordUse(use);
        assertThatThrownBy(()->memory.attributeUse(new MemoryAttributionRecord("att-1","alice","run-1","use-1","outcome-1",
                MemoryAttributionRecord.Relation.SUPPORTED,"task succeeded",List.of(),"verifier",NOW)))
                .isInstanceOf(IllegalArgumentException.class);

        MemoryAttributionRecord attribution=new MemoryAttributionRecord("att-1","alice","run-1","use-1","outcome-1",
                MemoryAttributionRecord.Relation.SUPPORTED,"this fact matched system-of-record inventory evidence",
                List.of("inventory-evidence-7"),"verifier",NOW);
        memory.attributeUse(attribution);
        MemoryAttributionRecord replay = new MemoryAttributionRecord("att-1","alice","run-1","use-1","outcome-1",
                MemoryAttributionRecord.Relation.SUPPORTED,"this fact matched system-of-record inventory evidence",
                List.of("inventory-evidence-7"),"verifier",NOW.plusSeconds(30));
        memory.attributeUse(replay);
        assertThat(memory.attribution("alice","use-1")).contains(attribution);
        assertThatThrownBy(()->memory.attributeUse(new MemoryAttributionRecord("att-2","alice","run-1","use-1","outcome-2",
                MemoryAttributionRecord.Relation.IRRELEVANT,"not used",List.of(),"verifier",NOW)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->memory.attributeUse(new MemoryAttributionRecord("att-3","bob","run-1","use-1","outcome-3",
                MemoryAttributionRecord.Relation.IRRELEVANT,"cross-subject",List.of(),"verifier",NOW)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void lifecycleCannotSkipAuthorityTransitions() {
        EpisodicMemoryRecord candidate=episode("ep-1","alice",MemoryLifecycle.CANDIDATE,NOW.plusSeconds(60));
        assertThatThrownBy(()->candidate.withLifecycle(MemoryLifecycle.SUPERSEDED,NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThat(candidate.withLifecycle(MemoryLifecycle.ACTIVE,NOW).lifecycle()).isEqualTo(MemoryLifecycle.ACTIVE);
    }

    private static EpisodicMemoryRecord episode(String id,String subject,MemoryLifecycle lifecycle,Instant retention) {
        Instant created = retention.isBefore(NOW) ? retention.minusSeconds(10) : NOW;
        return new EpisodicMemoryRecord(id,subject,created,"find an item","looked up item",EpisodicMemoryRecord.Outcome.UNKNOWN,
                List.of(),provenance(),lifecycle,created,created,retention);
    }
    private static SemanticMemoryRecord semantic(String id,String owner,MemoryLifecycle lifecycle,List<String> evidence,String supersedes) {
        return new SemanticMemoryRecord(id,owner,"delivery","preferred_method","pickup",0.9,evidence,provenance(),
                lifecycle,NOW,null,supersedes,NOW,NOW);
    }
    private void recordEvidence(String owner,String id,Evidence.Authority authority) {
        Evidence.EvidenceSource source = authority == Evidence.Authority.SYSTEM_OF_RECORD
                ? Evidence.EvidenceSource.FIRST_PARTY_SYSTEM : Evidence.EvidenceSource.USER;
        evidence.save(new Evidence(id,"profile","claim","value",source,authority,
                "profile",NOW,null,"explicitly recorded test evidence"),owner);
    }
    private static MemoryProvenance provenance() {
        return new MemoryProvenance(MemoryProvenance.Kind.USER_DECLARATION,"user-profile","user explicitly supplied this value",NOW);
    }
}
