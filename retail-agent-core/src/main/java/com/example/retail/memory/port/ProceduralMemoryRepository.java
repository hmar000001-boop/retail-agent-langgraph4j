package com.example.retail.memory.port;

import com.example.retail.memory.domain.ProceduralMemoryRecord;
import java.util.List;
import java.util.Optional;

public interface ProceduralMemoryRepository {
    /** Insert a candidate method once; replay never resets its accumulated validations. */
    void append(ProceduralMemoryRecord procedure);
    /** Explicit validation/lifecycle update after service validation. */
    void save(ProceduralMemoryRecord procedure);
    /** Compare-and-set a validation/lifecycle update against the version just read. */
    void update(ProceduralMemoryRecord procedure, java.time.Instant expectedUpdatedAt);
    Optional<ProceduralMemoryRecord> findProcedureById(String subjectId, String procedureId);
    List<ProceduralMemoryRecord> findApplicable(String subjectId, List<String> tags, int limit);
}
