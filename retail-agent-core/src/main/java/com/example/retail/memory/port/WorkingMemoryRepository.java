package com.example.retail.memory.port;

import com.example.retail.memory.domain.WorkingMemorySnapshot;
import java.util.Optional;

public interface WorkingMemoryRepository {
    Optional<WorkingMemorySnapshot> find(String subjectId, String executionId);
    /** expectedRevision=-1 means create-only; otherwise performs compare-and-set against the stored revision. */
    WorkingMemorySnapshot save(WorkingMemorySnapshot snapshot, long expectedRevision);
}
