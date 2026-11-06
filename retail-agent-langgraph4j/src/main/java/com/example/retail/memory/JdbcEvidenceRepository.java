package com.example.retail.memory;

import com.example.retail.agent.domain.Evidence;
import com.example.retail.agent.port.EvidenceRepository;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** PostgreSQL evidence store. Owner scope is a mandatory part of every primary key and read query. */
public final class JdbcEvidenceRepository implements EvidenceRepository {
    private final JdbcTemplate jdbc;
    public JdbcEvidenceRepository(JdbcTemplate jdbc) { this.jdbc=Objects.requireNonNull(jdbc,"jdbc"); }
    @Override public Optional<Evidence> findById(String evidenceId,String subjectId) {
        return jdbc.query("SELECT * FROM coala_evidence WHERE subject_id=? AND evidence_id=?",MAPPER,
                required(subjectId,"subjectId"),required(evidenceId,"evidenceId")).stream().findFirst();
    }
    @Override public List<Evidence> findByIds(List<String> evidenceIds,String subjectId) {
        if(evidenceIds==null||evidenceIds.isEmpty()) return List.of();
        List<String> ids=evidenceIds.stream().distinct().toList();
        String placeholders=String.join(",",java.util.Collections.nCopies(ids.size(),"?"));
        Object[] args=new Object[ids.size()+1]; args[0]=required(subjectId,"subjectId");
        for(int i=0;i<ids.size();i++) args[i+1]=required(ids.get(i),"evidenceId");
        return jdbc.query("SELECT * FROM coala_evidence WHERE subject_id=? AND evidence_id IN ("+placeholders+")",MAPPER,args);
    }
    @Override public void save(Evidence evidence,String subjectId) {
        Objects.requireNonNull(evidence,"evidence");
        jdbc.update("""
            INSERT INTO coala_evidence(subject_id,evidence_id,evidence_subject,predicate,evidence_value,source,authority,
              evidence_scope,observed_at,expires_at,provenance) VALUES(?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(subject_id,evidence_id) DO NOTHING
            """,required(subjectId,"subjectId"),evidence.id(),evidence.subject(),evidence.predicate(),evidence.value(),
                evidence.source().name(),evidence.authority().name(),evidence.scope(),ts(evidence.observedAt()),
                ts(evidence.expiresAt()),evidence.provenance());
        Evidence stored = findById(evidence.id(), subjectId).orElseThrow();
        if (!stored.sameAssertion(evidence))
            throw new IllegalStateException("evidence IDs are immutable within a subject scope");
    }
    private static final RowMapper<Evidence> MAPPER=(r,n)->new Evidence(r.getString("evidence_id"),r.getString("evidence_subject"),
            r.getString("predicate"),r.getString("evidence_value"),Evidence.EvidenceSource.valueOf(r.getString("source")),
            Evidence.Authority.valueOf(r.getString("authority")),r.getString("evidence_scope"),r.getTimestamp("observed_at").toInstant(),
            r.getTimestamp("expires_at")==null?null:r.getTimestamp("expires_at").toInstant(),r.getString("provenance"));
    private static Timestamp ts(java.time.Instant at){return at==null?null:Timestamp.from(at);}
    private static String required(String v,String n){if(v==null||v.isBlank())throw new IllegalArgumentException(n+" must not be blank");return v.trim();}
}
