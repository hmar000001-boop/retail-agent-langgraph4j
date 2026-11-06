package com.example.retail.memory;

import com.example.retail.memory.domain.*;
import com.example.retail.memory.port.CoalaMemoryRepository;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL implementation. Every lookup and mutation is scoped by subject_id. */
public final class JdbcCoalaMemoryRepository implements CoalaMemoryRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcCoalaMemoryRepository(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc=Objects.requireNonNull(jdbc,"jdbc");
        this.transactions=Objects.requireNonNull(transactions,"transactions");
    }

    @Override public void append(EpisodicMemoryRecord e) {
        jdbc.update("""
            INSERT INTO coala_episodic_memory(subject_id,id,occurred_at,goal,summary,outcome,evidence_refs,
              provenance_kind,provenance_source_id,provenance_description,provenance_captured_at,lifecycle,created_at,updated_at,retention_until)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(subject_id,id) DO NOTHING
            """, e.subjectId(),e.id(),ts(e.occurredAt()),e.goal(),e.summary(),e.outcome().name(),encode(e.evidenceIds()),
                e.provenance().kind().name(),e.provenance().sourceId(),e.provenance().description(),ts(e.provenance().capturedAt()),
                e.lifecycle().name(),ts(e.createdAt()),ts(e.updatedAt()),ts(e.retentionUntil()));
        EpisodicMemoryRecord stored = findEpisodeById(e.subjectId(),e.id()).orElseThrow();
        if (!stored.sameLogicalEvent(e)) throw new IllegalStateException("episodic memory ID collision");
    }

    @Override public void save(EpisodicMemoryRecord e) {
        jdbc.update("""
            INSERT INTO coala_episodic_memory(subject_id,id,occurred_at,goal,summary,outcome,evidence_refs,
              provenance_kind,provenance_source_id,provenance_description,provenance_captured_at,lifecycle,created_at,updated_at,retention_until)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(subject_id,id) DO UPDATE SET occurred_at=EXCLUDED.occurred_at,
              goal=EXCLUDED.goal,summary=EXCLUDED.summary,outcome=EXCLUDED.outcome,evidence_refs=EXCLUDED.evidence_refs,
              provenance_kind=EXCLUDED.provenance_kind,provenance_source_id=EXCLUDED.provenance_source_id,
              provenance_description=EXCLUDED.provenance_description,provenance_captured_at=EXCLUDED.provenance_captured_at,
              lifecycle=EXCLUDED.lifecycle,updated_at=EXCLUDED.updated_at,retention_until=EXCLUDED.retention_until
            """, e.subjectId(),e.id(),ts(e.occurredAt()),e.goal(),e.summary(),e.outcome().name(),encode(e.evidenceIds()),
                e.provenance().kind().name(),e.provenance().sourceId(),e.provenance().description(),ts(e.provenance().capturedAt()),
                e.lifecycle().name(),ts(e.createdAt()),ts(e.updatedAt()),ts(e.retentionUntil()));
    }
    @Override public void update(EpisodicMemoryRecord e,Instant expectedUpdatedAt) {
        int count=jdbc.update("UPDATE coala_episodic_memory SET occurred_at=?,goal=?,summary=?,outcome=?,evidence_refs=?,provenance_kind=?,provenance_source_id=?,provenance_description=?,provenance_captured_at=?,lifecycle=?,updated_at=?,retention_until=? WHERE subject_id=? AND id=? AND updated_at=?",
                ts(e.occurredAt()),e.goal(),e.summary(),e.outcome().name(),encode(e.evidenceIds()),e.provenance().kind().name(),
                e.provenance().sourceId(),e.provenance().description(),ts(e.provenance().capturedAt()),e.lifecycle().name(),
                ts(e.updatedAt()),ts(e.retentionUntil()),e.subjectId(),e.id(),ts(expectedUpdatedAt));
        if(count!=1) throw new java.util.ConcurrentModificationException("episodic memory revision conflict");
    }

    @Override public Optional<EpisodicMemoryRecord> findEpisodeById(String subjectId,String id) {
        return one("SELECT * FROM coala_episodic_memory WHERE subject_id=? AND id=?", EPISODE,subjectId,id);
    }
    @Override public List<EpisodicMemoryRecord> findRecent(String subjectId,Instant at,int limit) {
        positive(limit);
        return jdbc.query("SELECT * FROM coala_episodic_memory WHERE subject_id=? AND lifecycle='ACTIVE' AND occurred_at<=? AND (retention_until IS NULL OR retention_until>=?) ORDER BY occurred_at DESC LIMIT ?",
                EPISODE,subjectId,ts(at),ts(at),limit);
    }

    @Override public void append(SemanticMemoryRecord f) {
        jdbc.update("""
            INSERT INTO coala_semantic_memory(subject_id,id,subject_term,predicate,fact_value,confidence,evidence_refs,
              provenance_kind,provenance_source_id,provenance_description,provenance_captured_at,lifecycle,valid_from,valid_until,
              supersedes_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(subject_id,id) DO NOTHING
            """,f.subjectId(),f.id(),f.subject(),f.predicate(),f.value(),f.confidence(),encode(f.evidenceIds()),
                f.provenance().kind().name(),f.provenance().sourceId(),f.provenance().description(),ts(f.provenance().capturedAt()),
                f.lifecycle().name(),ts(f.validFrom()),ts(f.validUntil()),f.supersedesId(),ts(f.createdAt()),ts(f.updatedAt()));
        SemanticMemoryRecord stored = findSemanticById(f.subjectId(),f.id()).orElseThrow();
        if (!stored.sameLogicalFact(f)) throw new IllegalStateException("semantic memory ID collision");
    }

    @Override public void save(SemanticMemoryRecord f) {
        jdbc.update("""
            INSERT INTO coala_semantic_memory(subject_id,id,subject_term,predicate,fact_value,confidence,evidence_refs,
              provenance_kind,provenance_source_id,provenance_description,provenance_captured_at,lifecycle,valid_from,valid_until,
              supersedes_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(subject_id,id) DO UPDATE SET subject_term=EXCLUDED.subject_term,predicate=EXCLUDED.predicate,
              fact_value=EXCLUDED.fact_value,confidence=EXCLUDED.confidence,evidence_refs=EXCLUDED.evidence_refs,
              provenance_kind=EXCLUDED.provenance_kind,provenance_source_id=EXCLUDED.provenance_source_id,
              provenance_description=EXCLUDED.provenance_description,provenance_captured_at=EXCLUDED.provenance_captured_at,
              lifecycle=EXCLUDED.lifecycle,valid_from=EXCLUDED.valid_from,valid_until=EXCLUDED.valid_until,
              supersedes_id=EXCLUDED.supersedes_id,updated_at=EXCLUDED.updated_at
            """,f.subjectId(),f.id(),f.subject(),f.predicate(),f.value(),f.confidence(),encode(f.evidenceIds()),
                f.provenance().kind().name(),f.provenance().sourceId(),f.provenance().description(),ts(f.provenance().capturedAt()),
                f.lifecycle().name(),ts(f.validFrom()),ts(f.validUntil()),f.supersedesId(),ts(f.createdAt()),ts(f.updatedAt()));
    }
    @Override public void update(SemanticMemoryRecord f,Instant expectedUpdatedAt) {
        int count=jdbc.update("UPDATE coala_semantic_memory SET subject_term=?,predicate=?,fact_value=?,confidence=?,evidence_refs=?,provenance_kind=?,provenance_source_id=?,provenance_description=?,provenance_captured_at=?,lifecycle=?,valid_from=?,valid_until=?,supersedes_id=?,updated_at=? WHERE subject_id=? AND id=? AND updated_at=?",
                f.subject(),f.predicate(),f.value(),f.confidence(),encode(f.evidenceIds()),f.provenance().kind().name(),
                f.provenance().sourceId(),f.provenance().description(),ts(f.provenance().capturedAt()),f.lifecycle().name(),
                ts(f.validFrom()),ts(f.validUntil()),f.supersedesId(),ts(f.updatedAt()),f.subjectId(),f.id(),ts(expectedUpdatedAt));
        if(count!=1) throw new java.util.ConcurrentModificationException("semantic memory revision conflict");
    }

    @Override public Optional<SemanticMemoryRecord> findSemanticById(String subjectId,String id) {
        return one("SELECT * FROM coala_semantic_memory WHERE subject_id=? AND id=?", FACT,subjectId,id);
    }
    @Override public List<SemanticMemoryRecord> search(String subjectId,String query,Instant at,int limit) {
        positive(limit); required(query,"query");
        return jdbc.query("SELECT * FROM coala_semantic_memory WHERE subject_id=? AND lifecycle='ACTIVE' AND valid_from<=? AND (valid_until IS NULL OR valid_until>=?) AND POSITION(LOWER(?) IN LOWER(subject_term||' '||predicate||' '||fact_value)) > 0 ORDER BY confidence DESC LIMIT ?",
                FACT,subjectId,ts(at),ts(at),required(query,"query"),limit);
    }
    @Override public void replace(String subjectId,String supersededId,SemanticMemoryRecord replacement,Instant at) {
        if(!subjectId.equals(replacement.subjectId())||!Objects.equals(supersededId,replacement.supersedesId()))
            throw new IllegalArgumentException("replacement must be subject-scoped and reference the superseded fact");
        transactions.executeWithoutResult(status -> {
            SemanticMemoryRecord old=one("SELECT * FROM coala_semantic_memory WHERE subject_id=? AND id=? FOR UPDATE",FACT,subjectId,supersededId).orElseThrow(()->new NoSuchElementException("semantic fact not found in subject scope"));
            Optional<SemanticMemoryRecord> existingReplacement=findSemanticById(subjectId,replacement.id());
            if (existingReplacement.isPresent()) {
                if (old.lifecycle()==MemoryLifecycle.SUPERSEDED && existingReplacement.get().sameStoredVersion(replacement)) return;
                throw new IllegalStateException("replacement semantic memory ID already exists");
            }
            SemanticMemoryRecord superseded=old.withLifecycle(MemoryLifecycle.SUPERSEDED,at);
            save(superseded); save(replacement);
        });
    }

    @Override public void append(ProceduralMemoryRecord p) {
        jdbc.update("""
            INSERT INTO coala_procedural_memory(subject_id,id,name,description,applicability_tags,preconditions,steps,postconditions,
              successful_validations,failed_validations,successful_evidence_refs,failed_evidence_refs,provenance_kind,provenance_source_id,
              provenance_description,provenance_captured_at,lifecycle,created_at,updated_at,last_validated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(subject_id,id) DO NOTHING
            """,p.subjectId(),p.id(),p.name(),p.description(),encode(p.applicabilityTags()),encode(p.preconditions()),
                encode(p.steps()),encode(p.postconditions()),p.successfulValidations(),p.failedValidations(),encode(p.successfulEvidenceIds()),encode(p.failedEvidenceIds()),
                p.provenance().kind().name(),p.provenance().sourceId(),p.provenance().description(),ts(p.provenance().capturedAt()),
                p.lifecycle().name(),ts(p.createdAt()),ts(p.updatedAt()),ts(p.lastValidatedAt()));
        ProceduralMemoryRecord stored = findProcedureById(p.subjectId(),p.id()).orElseThrow();
        if (!stored.sameMethodDefinition(p)) throw new IllegalStateException("procedural memory ID collision");
    }

    @Override public void save(ProceduralMemoryRecord p) {
        jdbc.update("""
            INSERT INTO coala_procedural_memory(subject_id,id,name,description,applicability_tags,preconditions,steps,postconditions,
              successful_validations,failed_validations,successful_evidence_refs,failed_evidence_refs,provenance_kind,provenance_source_id,
              provenance_description,provenance_captured_at,lifecycle,created_at,updated_at,last_validated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(subject_id,id) DO UPDATE SET name=EXCLUDED.name,
              description=EXCLUDED.description,applicability_tags=EXCLUDED.applicability_tags,preconditions=EXCLUDED.preconditions,
              steps=EXCLUDED.steps,postconditions=EXCLUDED.postconditions,successful_validations=EXCLUDED.successful_validations,
              failed_validations=EXCLUDED.failed_validations,successful_evidence_refs=EXCLUDED.successful_evidence_refs,failed_evidence_refs=EXCLUDED.failed_evidence_refs,
              lifecycle=EXCLUDED.lifecycle,updated_at=EXCLUDED.updated_at,last_validated_at=EXCLUDED.last_validated_at
            """,p.subjectId(),p.id(),p.name(),p.description(),encode(p.applicabilityTags()),encode(p.preconditions()),
                encode(p.steps()),encode(p.postconditions()),p.successfulValidations(),p.failedValidations(),encode(p.successfulEvidenceIds()),encode(p.failedEvidenceIds()),
                p.provenance().kind().name(),p.provenance().sourceId(),p.provenance().description(),ts(p.provenance().capturedAt()),
                p.lifecycle().name(),ts(p.createdAt()),ts(p.updatedAt()),ts(p.lastValidatedAt()));
    }
    @Override public void update(ProceduralMemoryRecord p,Instant expectedUpdatedAt) {
        int count=jdbc.update("UPDATE coala_procedural_memory SET name=?,description=?,applicability_tags=?,preconditions=?,steps=?,postconditions=?,successful_validations=?,failed_validations=?,successful_evidence_refs=?,failed_evidence_refs=?,provenance_kind=?,provenance_source_id=?,provenance_description=?,provenance_captured_at=?,lifecycle=?,updated_at=?,last_validated_at=? WHERE subject_id=? AND id=? AND updated_at=?",
                p.name(),p.description(),encode(p.applicabilityTags()),encode(p.preconditions()),encode(p.steps()),encode(p.postconditions()),
                p.successfulValidations(),p.failedValidations(),encode(p.successfulEvidenceIds()),encode(p.failedEvidenceIds()),
                p.provenance().kind().name(),p.provenance().sourceId(),p.provenance().description(),ts(p.provenance().capturedAt()),
                p.lifecycle().name(),ts(p.updatedAt()),ts(p.lastValidatedAt()),p.subjectId(),p.id(),ts(expectedUpdatedAt));
        if(count!=1) throw new java.util.ConcurrentModificationException("procedural memory revision conflict");
    }

    @Override public Optional<ProceduralMemoryRecord> findProcedureById(String subjectId,String id) {
        return one("SELECT * FROM coala_procedural_memory WHERE subject_id=? AND id=?", PROCEDURE,subjectId,id);
    }
    @Override public List<ProceduralMemoryRecord> findApplicable(String subjectId,List<String> tags,int limit) {
        positive(limit);
        List<String> safeTags = tags == null ? List.of() : tags.stream().filter(Objects::nonNull)
                .map(String::trim).filter(tag -> !tag.isEmpty()).distinct().toList();
        StringBuilder sql = new StringBuilder("SELECT * FROM coala_procedural_memory WHERE subject_id=? AND lifecycle='ACTIVE' AND (applicability_tags=''");
        List<Object> args = new ArrayList<>();
        args.add(subjectId);
        for (String tag : safeTags) {
            sql.append(" OR POSITION(','||?||',' IN ','||applicability_tags||',') > 0");
            args.add(b64(tag));
        }
        sql.append(") ORDER BY successful_validations DESC LIMIT ?");
        args.add(limit);
        List<ProceduralMemoryRecord> candidates=jdbc.query(sql.toString(),PROCEDURE,args.toArray());
        return candidates.stream().filter(p->p.applicableTo(safeTags)).limit(limit).toList();
    }

    @Override public Optional<WorkingMemorySnapshot> find(String subjectId,String executionId) {
        return one("SELECT * FROM coala_working_memory WHERE subject_id=? AND execution_id=?",
                (rs,n)->new WorkingMemorySnapshot(rs.getString("subject_id"),rs.getString("execution_id"),rs.getLong("revision"),decodeMap(rs.getString("values_payload")),instant(rs,"updated_at"),instantNullable(rs,"expires_at")),subjectId,executionId);
    }
    @Override public WorkingMemorySnapshot save(WorkingMemorySnapshot s,long expectedRevision) {
        if(expectedRevision < -1) throw new IllegalArgumentException("expectedRevision cannot be less than -1");
        if(expectedRevision == -1) {
            try { jdbc.update("INSERT INTO coala_working_memory(subject_id,execution_id,revision,values_payload,updated_at,expires_at) VALUES(?,?,?,?,?,?)",
                    s.subjectId(),s.executionId(),s.revision(),encodeMap(s.values()),ts(s.updatedAt()),ts(s.expiresAt())); }
            catch(DuplicateKeyException ex) { throw new java.util.ConcurrentModificationException("working memory already exists"); }
        } else {
            if(s.revision()!=expectedRevision+1) throw new IllegalArgumentException("revision must advance exactly once");
            int count=jdbc.update("UPDATE coala_working_memory SET revision=?,values_payload=?,updated_at=?,expires_at=? WHERE subject_id=? AND execution_id=? AND revision=?",
                    s.revision(),encodeMap(s.values()),ts(s.updatedAt()),ts(s.expiresAt()),s.subjectId(),s.executionId(),expectedRevision);
            if(count!=1) throw new java.util.ConcurrentModificationException("working memory revision conflict");
        }
        return s;
    }

    @Override public void recordUse(MemoryUseRecord u) {
        jdbc.update("INSERT INTO coala_memory_uses(subject_id,id,execution_id,memory_id,memory_kind,stage,consumer,occurred_at) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(subject_id,id) DO NOTHING",
                u.subjectId(),u.id(),u.executionId(),u.memoryId(),u.memoryKind().name(),u.stage().name(),u.consumer(),ts(u.occurredAt()));
        MemoryUseRecord actual=findUse(u.subjectId(),u.id()).orElseThrow();
        if(!actual.sameLogicalUse(u)) throw new IllegalStateException("memory-use id collision");
    }
    @Override public Optional<MemoryUseRecord> findUse(String subjectId,String id) {
        return one("SELECT * FROM coala_memory_uses WHERE subject_id=? AND id=?",
                (rs,n)->new MemoryUseRecord(rs.getString("id"),rs.getString("subject_id"),rs.getString("execution_id"),
                        rs.getString("memory_id"),MemoryUseRecord.Kind.valueOf(rs.getString("memory_kind")),
                        MemoryUseRecord.Stage.valueOf(rs.getString("stage")),rs.getString("consumer"),instant(rs,"occurred_at")),subjectId,id);
    }
    @Override public void recordAttribution(MemoryAttributionRecord a) {
        jdbc.update("INSERT INTO coala_memory_attributions(subject_id,id,execution_id,use_id,outcome_id,relation,basis,evidence_refs,attributed_by,attributed_at) VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                a.subjectId(),a.id(),a.executionId(),a.useId(),a.outcomeId(),a.relation().name(),a.basis(),encode(a.evidenceIds()),a.attributedBy(),ts(a.attributedAt()));
        MemoryAttributionRecord stored = findAttributionByUse(a.subjectId(),a.useId()).orElse(null);
        if (stored == null || !stored.sameLogicalAttribution(a))
            throw new IllegalStateException("memory attribution already exists or attribution ID was reused");
    }
    @Override public Optional<MemoryAttributionRecord> findAttributionByUse(String subjectId,String useId) {
        return one("SELECT * FROM coala_memory_attributions WHERE subject_id=? AND use_id=?",
                (rs,n)->new MemoryAttributionRecord(rs.getString("id"),rs.getString("subject_id"),rs.getString("execution_id"),rs.getString("use_id"),
                        rs.getString("outcome_id"),MemoryAttributionRecord.Relation.valueOf(rs.getString("relation")),
                        rs.getString("basis"),decode(rs.getString("evidence_refs")),rs.getString("attributed_by"),instant(rs,"attributed_at")),subjectId,useId);
    }

    private <T> Optional<T> one(String sql,RowMapper<T> mapper,Object... args) {
        List<T> rows=jdbc.query(sql,mapper,args); return rows.stream().findFirst();
    }
    private static final RowMapper<EpisodicMemoryRecord> EPISODE=(r,n)->new EpisodicMemoryRecord(r.getString("id"),r.getString("subject_id"),instant(r,"occurred_at"),
            r.getString("goal"),r.getString("summary"),EpisodicMemoryRecord.Outcome.valueOf(r.getString("outcome")),decode(r.getString("evidence_refs")),
            provenance(r),MemoryLifecycle.valueOf(r.getString("lifecycle")),instant(r,"created_at"),instant(r,"updated_at"),instantNullable(r,"retention_until"));
    private static final RowMapper<SemanticMemoryRecord> FACT=(r,n)->new SemanticMemoryRecord(r.getString("id"),r.getString("subject_id"),r.getString("subject_term"),
            r.getString("predicate"),r.getString("fact_value"),r.getDouble("confidence"),decode(r.getString("evidence_refs")),provenance(r),
            MemoryLifecycle.valueOf(r.getString("lifecycle")),instant(r,"valid_from"),instantNullable(r,"valid_until"),r.getString("supersedes_id"),
            instant(r,"created_at"),instant(r,"updated_at"));
    private static final RowMapper<ProceduralMemoryRecord> PROCEDURE=(r,n)->new ProceduralMemoryRecord(r.getString("id"),r.getString("subject_id"),r.getString("name"),
            r.getString("description"),decode(r.getString("applicability_tags")),decode(r.getString("preconditions")),decode(r.getString("steps")),
            decode(r.getString("postconditions")),r.getInt("successful_validations"),r.getInt("failed_validations"),decode(r.getString("successful_evidence_refs")),decode(r.getString("failed_evidence_refs")),
            provenance(r),MemoryLifecycle.valueOf(r.getString("lifecycle")),instant(r,"created_at"),instant(r,"updated_at"),instantNullable(r,"last_validated_at"));
    private static MemoryProvenance provenance(ResultSet r)throws SQLException { return new MemoryProvenance(MemoryProvenance.Kind.valueOf(r.getString("provenance_kind")),
            r.getString("provenance_source_id"),r.getString("provenance_description"),instant(r,"provenance_captured_at")); }
    private static Timestamp ts(Instant i) { return i==null?null:Timestamp.from(i); }
    private static Instant instant(ResultSet r,String column)throws SQLException { return r.getTimestamp(column).toInstant(); }
    private static Instant instantNullable(ResultSet r,String column)throws SQLException { Timestamp t=r.getTimestamp(column); return t==null?null:t.toInstant(); }
    private static String encode(List<String> values) { return values==null?"":values.stream().map(JdbcCoalaMemoryRepository::b64).collect(Collectors.joining(",")); }
    private static List<String> decode(String value) { if(value==null||value.isEmpty())return List.of(); return Arrays.stream(value.split(",",-1)).map(JdbcCoalaMemoryRepository::unb64).toList(); }
    private static String encodeMap(Map<String,String> values) { return values.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e->b64(e.getKey())+":"+b64(e.getValue())).collect(Collectors.joining(",")); }
    private static Map<String,String> decodeMap(String value) { if(value==null||value.isEmpty())return Map.of(); Map<String,String> out=new LinkedHashMap<>(); for(String part:value.split(",")){int i=part.indexOf(':');if(i<1)throw new IllegalStateException("corrupt working-memory payload");out.put(unb64(part.substring(0,i)),unb64(part.substring(i+1)));} return Map.copyOf(out); }
    private static String b64(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static String unb64(String value) { return new String(Base64.getUrlDecoder().decode(value),StandardCharsets.UTF_8); }
    private static String required(String v,String n){if(v==null||v.isBlank())throw new IllegalArgumentException(n+" must not be blank");return v.trim();}
    private static void positive(int n){if(n<1)throw new IllegalArgumentException("limit must be positive");}
}
