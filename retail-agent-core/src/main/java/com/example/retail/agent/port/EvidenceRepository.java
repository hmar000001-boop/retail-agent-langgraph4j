package com.example.retail.agent.port;

import com.example.retail.agent.domain.Evidence;
import java.util.List;
import java.util.Optional;

/** Persistence/search boundary for sourced evidence. Implementations must enforce caller scope. */
public interface EvidenceRepository {
    Optional<Evidence> findById(String evidenceId, String subjectId);
    List<Evidence> findByIds(List<String> evidenceIds, String subjectId);
    void save(Evidence evidence, String subjectId);
}
