package com.example.retail.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.retail.config.AgentConfiguration;
import com.example.retail.domain.ProductCatalog;
import com.example.retail.llm.HashEmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;

/** Guards the assumption the scripted scenarios rely on: the deterministic embeddings rank sensibly. */
class CatalogSearchTest {
    @Test
    void trailRunningShoesQuery_ranksFootwearFirst() {
        var store = AgentConfiguration.indexedVectorStore(new HashEmbeddingModel(), new ProductCatalog());
        var hits = store.similaritySearch(SearchRequest.builder().query("I need trail running shoes").topK(3).build());

        assertThat(hits).isNotEmpty();
        Document top = hits.get(0);
        assertThat(top.getMetadata().get("category")).isEqualTo("footwear");
    }
}
