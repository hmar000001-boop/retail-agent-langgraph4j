package com.example.retail.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.retail.config.AgentConfiguration;
import com.example.retail.domain.EpisodicMemory;
import com.example.retail.domain.OrderHistory;
import com.example.retail.domain.ProductCatalog;
import com.example.retail.core.ResourceBudget;
import com.example.retail.llm.HashEmbeddingModel;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** Regression coverage for the clarification path restored after the graph/node split. */
class RetailGraphNodesClarificationTest {

    @Test
    void exhaustedPlanningWithoutEvidenceRoutesToClarification() {
        ProductCatalog catalog = new ProductCatalog();
        LlmClient llm = new LlmClient((task, system, user) -> CompletableFuture.completedFuture("{}"));
        RetailGraphNodes nodes = new RetailGraphNodes(llm,
                AgentConfiguration.indexedVectorStore(new HashEmbeddingModel(), catalog),
                catalog, new OrderHistory(), new EpisodicMemory(),
                new AgentSettings(3, 0.4, 4, 0, 3, 8, 2, 180, 260));
        RetailState state = new RetailState(Map.of(
                RetailState.QUERY, "trail running shoes",
                RetailState.BUDGET, ResourceBudget.start(0, 3, 8)));

        Map<String, Object> update = nodes.rejectAndReplan(state, "no admissible proposal");

        assertEquals("clarify", update.get(RetailState.ROUTE));
        assertTrue(update.get(RetailState.CLARIFICATION) instanceof String
                && !((String) update.get(RetailState.CLARIFICATION)).isBlank());
        assertTrue(((java.util.List<?>) update.get(RetailState.TRACE)).stream()
                .anyMatch(entry -> entry.toString().contains("select:clarify-after-rejected-planning")));
    }
}
