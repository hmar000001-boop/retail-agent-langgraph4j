package com.example.retail.config;

import com.example.retail.agent.AgentSettings;
import com.example.retail.agent.RetailAgent;
import com.example.retail.domain.EpisodicMemory;
import com.example.retail.domain.OrderHistory;
import com.example.retail.domain.Product;
import com.example.retail.domain.ProductCatalog;
import com.example.retail.llm.HashEmbeddingModel;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({LlmProperties.class, AgentSettings.class})
public class AgentConfiguration {

    /**
     * Spring AI's OpenAI auto-configuration only creates a ChatModel when app.llm.provider=real
     * (see spring.ai.model.chat in application.yml), so the provider is only resolved in that case.
     */
    @Bean
    AgentLlm agentLlm(LlmProperties props, ObjectProvider<ChatModel> providerModel) {
        return new AgentLlm(LlmStack.build(props, providerModel::getObject));
    }

    /** Deterministic local embeddings unless app.llm.embeddings=real (then Spring AI's OpenAI one is used). */
    @Bean
    @ConditionalOnProperty(name = "app.llm.embeddings", havingValue = "hash", matchIfMissing = true)
    EmbeddingModel hashEmbeddingModel() {
        return new HashEmbeddingModel();
    }

    @Bean
    VectorStore vectorStore(EmbeddingModel embeddingModel, ProductCatalog catalog) {
        return indexedVectorStore(embeddingModel, catalog);
    }

    /** CoALA semantic memory: the catalog, embedded. */
    public static VectorStore indexedVectorStore(EmbeddingModel embeddingModel, ProductCatalog catalog) {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        store.add(catalog.all().stream().map(AgentConfiguration::toDocument).toList());
        return store;
    }

    private static Document toDocument(Product p) {
        return Document.builder()
                .id(p.id())
                .text(p.name() + ". " + p.description())
                .metadata(Map.<String, Object>of("productId", p.id(), "category", p.category()))
                .build();
    }

    @Bean
    ProductCatalog productCatalog() {
        return new ProductCatalog();
    }

    @Bean
    OrderHistory orderHistory() {
        return new OrderHistory();
    }

    @Bean
    EpisodicMemory episodicMemory() {
        return new EpisodicMemory();
    }

    @Bean
    RetailAgent retailAgent(AgentLlm llm, VectorStore vectorStore, ProductCatalog catalog,
                            OrderHistory orders, EpisodicMemory memory, AgentSettings settings) {
        return new RetailAgent(llm.model(), vectorStore, catalog, orders, memory, settings);
    }
}
