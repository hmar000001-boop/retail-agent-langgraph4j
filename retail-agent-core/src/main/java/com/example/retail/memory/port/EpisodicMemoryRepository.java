package com.example.retail.memory.port;

import com.example.retail.memory.domain.EpisodicMemoryRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface EpisodicMemoryRepository {
    /** Insert once; an exact logical replay is a no-op, conflicting reuse of an event ID is rejected. */
    void append(EpisodicMemoryRecord episode);
    /** Explicit update path for lifecycle/retention administration. */
    void save(EpisodicMemoryRecord episode);
    /** Compare-and-set a lifecycle/retention update against the version just read. */
    void update(EpisodicMemoryRecord episode, Instant expectedUpdatedAt);
    Optional<EpisodicMemoryRecord> findEpisodeById(String subjectId, String episodeId);
    List<EpisodicMemoryRecord> findRecent(String subjectId, Instant at, int limit);
}
