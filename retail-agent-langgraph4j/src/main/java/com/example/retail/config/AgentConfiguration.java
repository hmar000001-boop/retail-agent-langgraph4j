package com.example.retail.config;

import com.example.retail.agent.AgentSettings;
import com.example.retail.agent.LangChain4jLlmGateway;
import com.example.retail.agent.LlmGateway;
import com.example.retail.agent.RetailAgent;
import com.example.retail.agent.SpringAiLlmGateway;
import com.example.retail.agent.RecordingLlmGateway;
import com.example.retail.llm.CassetteStore;
import java.nio.file.Path;
import com.example.retail.agent.RetailState;
import com.example.retail.agent.checkpoint.SynchronizedMemorySaver;
import com.example.retail.domain.EpisodicMemory;
import com.example.retail.domain.OrderHistory;
import com.example.retail.domain.Product;
import com.example.retail.domain.ProductCatalog;
import com.example.retail.llm.HashEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.ai.chat.model.ChatModel;

@Configuration
@EnableConfigurationProperties({LlmProperties.class, AgentSettings.class, GraphCheckpointProperties.class})
public class AgentConfiguration {

    @Bean(destroyMethod = "shutdown")
    ExecutorService simulatedModelExecutor() {
        int threads = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
        return Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "retail-simulated-llm");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Scripted/replay remains deterministic; real model calls use native LangChain4j async I/O. */
    @Bean
    AgentLlm agentLlm(LlmProperties props, ExecutorService simulatedModelExecutor) {
        if (props.provider() == LlmProperties.Provider.SIMULATED) {
            ChatModel simulator = LlmStack.build(props, () -> {
                throw new IllegalStateException("No Spring AI real chat model is used by the primary runtime");
            });
            return new AgentLlm(new SpringAiLlmGateway(simulator, simulatedModelExecutor));
        }
        if (props.apiKey().isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY is required when app.llm.provider=real");
        }
        var model = OpenAiChatModel.builder()
                .apiKey(props.apiKey())
                .modelName(props.modelName())
                .timeout(props.timeout())
                .maxRetries(props.maxRetries())
                // Prompt/response payload logging is intentionally disabled by default.
                .logRequests(false)
                .logResponses(false)
                .build();
        LlmGateway gateway = new LangChain4jLlmGateway(model);
        if (props.record()) {
            gateway = new RecordingLlmGateway(gateway, new CassetteStore(Path.of(props.cassetteDir())));
        }
        return new AgentLlm(gateway);
    }

    @Bean
    @ConditionalOnProperty(name = "app.llm.embeddings", havingValue = "hash", matchIfMissing = true)
    EmbeddingModel hashEmbeddingModel() { return new HashEmbeddingModel(); }

    @Bean
    VectorStore vectorStore(EmbeddingModel embeddingModel, ProductCatalog catalog) {
        return indexedVectorStore(embeddingModel, catalog);
    }

    /** Existing Spring AI semantic-store adapter is retained for this phase; durable hybrid RAG is a later phase. */
    public static VectorStore indexedVectorStore(EmbeddingModel embeddingModel, ProductCatalog catalog) {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        store.add(catalog.all().stream().map(AgentConfiguration::toDocument).toList());
        return store;
    }

    private static Document toDocument(Product p) {
        return Document.builder().id(p.id()).text(p.name() + ". " + p.description())
                .metadata(Map.<String, Object>of("productId", p.id(), "category", p.category())).build();
    }

    @Bean ProductCatalog productCatalog() { return new ProductCatalog(); }
    @Bean OrderHistory orderHistory() { return new OrderHistory(); }
    @Bean EpisodicMemory episodicMemory() { return new EpisodicMemory(); }

    @Bean
    @ConditionalOnProperty(name = "app.graph.checkpoint.backend", havingValue = "memory", matchIfMissing = true)
    BaseCheckpointSaver memoryCheckpointSaver() { return new SynchronizedMemorySaver(); }

    @Bean
    @ConditionalOnProperty(name = "app.graph.checkpoint.backend", havingValue = "postgres")
    BaseCheckpointSaver postgresCheckpointSaver(GraphCheckpointProperties props) {
        return PostgresSaver.builder()
                .host(props.host()).port(props.port()).user(props.username()).password(props.password())
                .database(props.database())
                .stateSerializer(new ObjectStreamStateSerializer<>(RetailState::new))
                .createTables(props.createTables())
                .build();
    }

    @Bean
    RetailAgent retailAgent(AgentLlm llm, VectorStore vectorStore, ProductCatalog catalog,
                            OrderHistory orders, EpisodicMemory memory, AgentSettings settings,
                            BaseCheckpointSaver checkpointSaver) {
        return new RetailAgent(llm.gateway(), vectorStore, catalog, orders, memory, settings, checkpointSaver);
    }

    /** Do not let a production profile silently start with ephemeral in-memory checkpoints. */
    @Bean
    @Profile("production")
    org.springframework.beans.factory.SmartInitializingSingleton requireDurableCheckpoints(GraphCheckpointProperties props) {
        return () -> {
            if (props.backend() != GraphCheckpointProperties.Backend.POSTGRES) {
                throw new IllegalStateException("The production profile requires app.graph.checkpoint.backend=postgres");
            }
        };
    }
}
