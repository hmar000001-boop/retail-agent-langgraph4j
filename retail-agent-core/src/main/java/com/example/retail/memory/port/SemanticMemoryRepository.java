package com.example.retail.memory.port;

import com.example.retail.memory.domain.SemanticMemoryRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SemanticMemoryRepository {
    /** Insert once; replaying the same logical fact preserves its current lifecycle and evidence. */
    void append(SemanticMemoryRecord fact);
    /** Explicit state/lifecycle update after service validation. */
    void save(SemanticMemoryRecord fact);
    /** Compare-and-set an activation/administrative update against the version just read. */
    void update(SemanticMemoryRecord fact, Instant expectedUpdatedAt);
    Optional<SemanticMemoryRecord> findSemanticById(String subjectId, String factId);
    List<SemanticMemoryRecord> search(String subjectId, String query, Instant at, int limit);
    void replace(String subjectId, String supersededId, SemanticMemoryRecord replacement, Instant at);
}
