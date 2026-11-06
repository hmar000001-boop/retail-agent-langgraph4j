package com.example.retail.config;

import com.example.retail.llm.FaultRule;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Model selection and transport settings. Real chat inference is provided by LangChain4j. */
@ConfigurationProperties(prefix = "app.llm")
public record LlmProperties(
        @DefaultValue("simulated") Provider provider,
        @DefaultValue("hash") Embeddings embeddings,
        @DefaultValue("scripted") Simulation simulation,
        @DefaultValue("src/test/resources/cassettes") String cassetteDir,
        @DefaultValue("false") boolean record,
        @DefaultValue List<FaultRule> faults,
        @DefaultValue("gpt-4o-mini") String modelName,
        @DefaultValue("") String apiKey,
        @DefaultValue("60s") Duration timeout,
        @DefaultValue("2") int maxRetries) {

    public LlmProperties {
        if (modelName == null || modelName.isBlank()) throw new IllegalArgumentException("app.llm.model-name must not be blank");
        if (timeout == null || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("app.llm.timeout must be positive");
        if (maxRetries < 0 || maxRetries > 8) throw new IllegalArgumentException("app.llm.max-retries must be in [0,8]");
        apiKey = apiKey == null ? "" : apiKey;
    }

    public enum Provider { REAL, SIMULATED }
    public enum Embeddings { HASH, REAL }
    public enum Simulation { SCRIPTED, REPLAY }
}
