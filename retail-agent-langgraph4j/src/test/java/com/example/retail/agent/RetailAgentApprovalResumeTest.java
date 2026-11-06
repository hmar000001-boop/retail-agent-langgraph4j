package com.example.retail.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.retail.agent.domain.ActionDefinition;
import com.example.retail.agent.domain.ActionOutcome;
import com.example.retail.agent.domain.PendingActionApproval;
import com.example.retail.agent.port.ActionGateway;
import com.example.retail.agent.port.ActionRegistry;
import com.example.retail.domain.EpisodicMemory;
import com.example.retail.domain.OrderHistory;
import com.example.retail.domain.ProductCatalog;
import com.example.retail.llm.HashEmbeddingModel;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;

/** Exercises the real compiled-graph interrupt, owner binding, checkpoint update and resume path. */
class RetailAgentApprovalResumeTest {
    private static final String SUBJECT = "approval-owner-17";
    private static final String THREAD = "approval-thread-17";
    private static final String QUERY = "trail running shoes";

    // Reuse an otherwise allowed planner action id, but register it as an external side-effecting
    // capability. This isolates approval mechanics without pretending the demo has a real publisher.
    private static final ActionDefinition EXTERNAL_SEARCH = new ActionDefinition(
            "search_catalog", "External publication action used by the approval integration test",
            ActionDefinition.ActionKind.EXTERNAL, ActionDefinition.RiskLevel.LOW,
            List.of(ActionDefinition.Effect.COMMUNICATE), List.of(), Duration.ofSeconds(5),
            false, false, false, false, true, false);

    @Test
    void graphPausesForApprovalAndResumesOnlyForTheAuthenticatedThreadOwner() {
        AtomicInteger executions = new AtomicInteger();
        RetailAgent agent = agent(executions);

        AgentResult pending = agent.start(THREAD, SUBJECT, QUERY);

        assertThat(pending.awaitingApproval()).isTrue();
        assertThat(pending.approvalStatus()).isEqualTo("PENDING");
        PendingActionApproval request = pending.approvalRequest().orElseThrow();
        assertThat(request.actionId()).isEqualTo(EXTERNAL_SEARCH.id());
        assertThat(request.description()).isEqualTo(EXTERNAL_SEARCH.description());
        assertThat(request.kind()).isEqualTo(ActionDefinition.ActionKind.EXTERNAL);
        assertThat(request.risk()).isEqualTo(ActionDefinition.RiskLevel.LOW);
        assertThat(request.effects()).containsExactly(ActionDefinition.Effect.COMMUNICATE);
        assertThat(request.destructive()).isFalse();
        assertThat(request.irreversible()).isFalse();
        assertThat(request.financial()).isFalse();
        assertThat(request.externalSharing()).isTrue();
        assertThat(request.arguments()).containsEntry("query", QUERY);
        assertThat(request.subjectId()).isEqualTo(SUBJECT);
        assertThat(executions).hasValue(0);
        assertThatThrownBy(() -> agent.resumeApproval(THREAD, "attacker", request.approvalId(), true))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> agent.resumeApproval(THREAD, SUBJECT, "stale-approval-id", true))
                .isInstanceOf(SecurityException.class);
        assertThat(executions).hasValue(0);

        AgentResult completed = agent.resumeApproval(THREAD, SUBJECT, request.approvalId(), true);

        assertThat(executions).hasValue(1);
        assertThat(completed.awaitingApproval()).isFalse();
        assertThat(completed.approvalStatus()).isEqualTo("APPROVED_EXECUTED");
        int executionTrace = completed.trace().indexOf("execute:search_catalog:status=SUCCEEDED:code=OK:approved=true:cycle=1");
        int workspaceTrace = lastIndexStartingWith(completed.trace(), "workspace:admit=");
        int reflectionTrace = lastIndexStartingWith(completed.trace(), "reflect:cycle=");
        assertThat(executionTrace).isGreaterThanOrEqualTo(0);
        assertThat(workspaceTrace).isGreaterThan(executionTrace);
        assertThat(reflectionTrace).isGreaterThan(workspaceTrace);
    }

    @Test
    void deniedApprovalResumesGraphWithoutCallingActionGateway() {
        AtomicInteger executions = new AtomicInteger();
        RetailAgent agent = agent(executions);
        AgentResult pending = agent.start("approval-denied-thread", SUBJECT, QUERY);

        assertThat(pending.awaitingApproval()).isTrue();
        String approvalId = pending.approvalRequest().orElseThrow().approvalId();
        AgentResult denied = agent.resumeApproval("approval-denied-thread", SUBJECT, approvalId, false);

        assertThat(executions).hasValue(0);
        assertThat(denied.awaitingApproval()).isFalse();
        assertThat(denied.approvalStatus()).isEqualTo("DENIED");
    }

    private static int lastIndexStartingWith(List<String> values, String prefix) {
        for (int i = values.size() - 1; i >= 0; i--) {
            if (values.get(i).startsWith(prefix)) return i;
        }
        return -1;
    }

    private static RetailAgent agent(AtomicInteger executions) {
        ProductCatalog catalog = new ProductCatalog();
        VectorStore vectors = com.example.retail.config.AgentConfiguration.indexedVectorStore(
                new HashEmbeddingModel(), catalog);
        LlmGateway llm = (task, system, user) -> java.util.concurrent.CompletableFuture.completedFuture(
                switch (task) {
                    case "PROPOSE" -> "{\"proposals\":[{\"tool\":\"search_catalog\",\"args\":\"trail running shoes\",\"rationale\":\"run the registered action\",\"confidence\":0.8}]}";
                    case "REFLECT" -> "{\"goalSatisfied\":false,\"summary\":\"The authorized action completed.\"}";
                    case "ANSWER" -> "The action completed.";
                    default -> throw new IllegalArgumentException("Unexpected model task: " + task);
                });
        ActionRegistry registry = new ActionRegistry() {
            @Override public Optional<ActionDefinition> find(String id) {
                return EXTERNAL_SEARCH.id().equals(id) ? Optional.of(EXTERNAL_SEARCH) : Optional.empty();
            }
            @Override public java.util.Collection<ActionDefinition> availableTo(String subjectId) {
                return List.of(EXTERNAL_SEARCH);
            }
        };
        ActionGateway gateway = invocation -> {
            executions.incrementAndGet();
            return new ActionOutcome(invocation.invocationId(), ActionOutcome.Status.SUCCEEDED,
                    "OK", "external action executed", List.of(), Instant.now(), false);
        };
        return new RetailAgent(llm, vectors, catalog, new OrderHistory(), new EpisodicMemory(),
                new AgentSettings(1, 0.4, 4, 1, 3, 8, 2, 180, 260),
                new com.example.retail.agent.checkpoint.SynchronizedMemorySaver(), registry, gateway);
    }
}
