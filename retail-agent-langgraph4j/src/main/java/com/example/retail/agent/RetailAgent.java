package com.example.retail.agent;

import com.example.retail.domain.EpisodicMemory;
import com.example.retail.domain.OrderHistory;
import com.example.retail.domain.ProductCatalog;
import com.example.retail.memory.service.CoalaMemoryService;
import com.example.retail.agent.port.ActionGateway;
import com.example.retail.agent.port.ActionRegistry;
import java.util.concurrent.ForkJoinPool;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.VectorStore;

/** Public application facade. Graph topology belongs to RetailAgentGraph; node behavior belongs to RetailGraphNodes. */
public final class RetailAgent {
    private final LlmClient llm;
    private final RetailAgentGraph graph;

    /** Compatibility constructor retained for deterministic Spring AI tests and replay tooling. */
    public RetailAgent(ChatModel model, VectorStore vectorStore, ProductCatalog catalog, OrderHistory orders,
                       EpisodicMemory memory, AgentSettings settings) {
        this(new SpringAiLlmGateway(model, ForkJoinPool.commonPool()), vectorStore, catalog, orders, memory, settings,
                new com.example.retail.agent.checkpoint.SynchronizedMemorySaver());
    }

    public RetailAgent(LlmGateway gateway, VectorStore vectorStore, ProductCatalog catalog, OrderHistory orders,
                       EpisodicMemory memory, AgentSettings settings, BaseCheckpointSaver checkpointSaver) {
        this(gateway, vectorStore, catalog, orders, memory, settings, checkpointSaver,
                new RetailActionRegistry(), null);
    }

    /** Primary constructor: CoALA memory is independent of graph checkpoints and caller-local caches. */
    public RetailAgent(LlmGateway gateway, VectorStore vectorStore, ProductCatalog catalog, OrderHistory orders,
                       CoalaMemoryService memory, AgentSettings settings, BaseCheckpointSaver checkpointSaver) {
        this(gateway, vectorStore, catalog, orders, memory, settings, checkpointSaver,
                new RetailActionRegistry(), null);
    }

    public RetailAgent(LlmGateway gateway, VectorStore vectorStore, ProductCatalog catalog, OrderHistory orders,
                       CoalaMemoryService memory, AgentSettings settings, BaseCheckpointSaver checkpointSaver,
                       ActionRegistry actionRegistry, ActionGateway actionGateway) {
        this.llm = new LlmClient(gateway);
        RetailGraphNodes nodes = new RetailGraphNodes(llm, vectorStore, catalog, orders, memory, settings,
                actionRegistry, actionGateway);
        this.graph = new RetailAgentGraph(nodes, settings, checkpointSaver);
    }

    /** Injectable authority boundary used by infrastructure adapters and graph-level contract tests. */
    public RetailAgent(LlmGateway gateway, VectorStore vectorStore, ProductCatalog catalog, OrderHistory orders,
                       EpisodicMemory memory, AgentSettings settings, BaseCheckpointSaver checkpointSaver,
                       ActionRegistry actionRegistry, ActionGateway actionGateway) {
        this.llm = new LlmClient(gateway);
        RetailGraphNodes nodes = new RetailGraphNodes(llm, vectorStore, catalog, orders, memory, settings,
                actionRegistry, actionGateway);
        this.graph = new RetailAgentGraph(nodes, settings, checkpointSaver);
    }

    public AgentResult ask(String customerId, String query) {
        return graph.ask(customerId, query);
    }

    /** Starts or resumes a caller-chosen graph thread id; useful for durable application sessions. */
    public AgentResult start(String threadId, String customerId, String query) {
        return graph.start(threadId, customerId, query);
    }

    public AgentResult resumeApproval(String threadId, String customerId, String approvalId, boolean approved) {
        return graph.resumeApproval(threadId, customerId, approvalId, approved);
    }

    public String graphDiagram() {
        return graph.graphDiagram();
    }

    public int llmRetries() {
        return llm.retries();
    }

}
