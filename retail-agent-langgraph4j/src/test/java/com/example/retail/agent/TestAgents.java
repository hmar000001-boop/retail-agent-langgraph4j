package com.example.retail.agent;

import com.example.retail.config.AgentConfiguration;
import com.example.retail.domain.EpisodicMemory;
import com.example.retail.domain.OrderHistory;
import com.example.retail.domain.ProductCatalog;
import com.example.retail.llm.HashEmbeddingModel;
import org.springframework.ai.chat.model.ChatModel;

/** Builds an agent by hand (no Spring context) so tests can plug in any ChatModel. */
final class TestAgents {
    static final String CUSTOMER = "c-100";
    static final String QUERY = "I need trail running shoes";

    private TestAgents() {}

    static RetailAgent agent(ChatModel model) {
        return agent(model, new EpisodicMemory());
    }

    static RetailAgent agent(ChatModel model, EpisodicMemory memory) {
        ProductCatalog catalog = new ProductCatalog();
        return new RetailAgent(model,
                AgentConfiguration.indexedVectorStore(new HashEmbeddingModel(), catalog),
                catalog, new OrderHistory(), memory, new AgentSettings(3, 0.4, 4, 3, 3, 8, 2, 180, 260));
    }

    static Throwable root(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t;
    }
}
