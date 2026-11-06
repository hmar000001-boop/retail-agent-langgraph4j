package com.example.retail.memory.port;

/** Aggregate port used by the application service; implementations must enforce subject scoping. */
public interface CoalaMemoryRepository extends EpisodicMemoryRepository, SemanticMemoryRepository,
        ProceduralMemoryRepository, WorkingMemoryRepository, MemoryAttributionRepository { }
