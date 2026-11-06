package com.example.retail.memory.port;

import com.example.retail.memory.domain.MemoryAttributionRecord;
import com.example.retail.memory.domain.MemoryUseRecord;
import java.util.Optional;

public interface MemoryAttributionRepository {
    void recordUse(MemoryUseRecord use);
    Optional<MemoryUseRecord> findUse(String subjectId, String useId);
    void recordAttribution(MemoryAttributionRecord attribution);
    Optional<MemoryAttributionRecord> findAttributionByUse(String subjectId, String useId);
}
