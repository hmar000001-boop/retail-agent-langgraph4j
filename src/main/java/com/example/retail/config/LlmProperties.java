package com.example.retail.config;

import com.example.retail.llm.FaultRule;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** The test-time switch. {@code app.llm.provider=real|simulated} is the flag. */
@ConfigurationProperties(prefix = "app.llm")
public record LlmProperties(
        @DefaultValue("simulated") Provider provider,
        @DefaultValue("hash") Embeddings embeddings,
        @DefaultValue("scripted") Simulation simulation,
        @DefaultValue("src/test/resources/cassettes") String cassetteDir,
        @DefaultValue("false") boolean record,
        @DefaultValue List<FaultRule> faults) {

    public enum Provider { REAL, SIMULATED }

    public enum Embeddings { HASH, REAL }

    public enum Simulation { SCRIPTED, REPLAY }
}
