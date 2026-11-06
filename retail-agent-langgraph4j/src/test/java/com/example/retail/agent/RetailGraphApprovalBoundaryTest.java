package com.example.retail.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.retail.agent.domain.ActionDefinition;
import com.example.retail.agent.domain.ActionOutcome;
import com.example.retail.agent.domain.PendingActionApproval;
import com.example.retail.agent.port.ActionGateway;
import com.example.retail.agent.port.ActionRegistry;
import com.example.retail.core.IntentionRecord;
import com.example.retail.core.ResourceBudget;
import com.example.retail.domain.EpisodicMemory;
import com.example.retail.domain.OrderHistory;
import com.example.retail.domain.ProductCatalog;
import com.example.retail.llm.HashEmbeddingModel;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RetailGraphApprovalBoundaryTest {
    private static final String SUBJECT = "customer-42";
    private static final ActionDefinition EXTERNAL_ACTION = new ActionDefinition(
            "external_publish", "Publish information externally", ActionDefinition.ActionKind.EXTERNAL,
            ActionDefinition.RiskLevel.LOW, List.of(ActionDefinition.Effect.COMMUNICATE), List.of(),
            Duration.ofSeconds(5), false, false, false, false, true, false);

    @Test
    void approvedPendingActionCanReachGatewayOnlyAfterSubjectBoundApproval() {
        AtomicInteger executions = new AtomicInteger();
        RetailGraphNodes nodes = nodes(EXTERNAL_ACTION, invocation -> {
            executions.incrementAndGet();
            return new ActionOutcome(invocation.invocationId(), ActionOutcome.Status.SUCCEEDED,
                    "OK", "published", List.of(), Instant.now(), true);
        });

        Map<String, Object> update = nodes.executeApproved(state("PENDING", "APPROVED", SUBJECT));

        assertThat(executions).hasValue(1);
        assertThat(update).containsEntry(RetailState.APPROVAL_STATUS, "APPROVED_EXECUTED");
        assertThat(update.get(RetailState.LAST_ACTION_OUTCOME)).isInstanceOf(ActionOutcome.class);
    }

    @Test
    void missingOrDeniedApprovalNeverCallsActionGateway() {
        AtomicInteger executions = new AtomicInteger();
        RetailGraphNodes nodes = nodes(EXTERNAL_ACTION, invocation -> {
            executions.incrementAndGet();
            return new ActionOutcome(invocation.invocationId(), ActionOutcome.Status.SUCCEEDED,
                    "OK", "published", List.of(), Instant.now(), true);
        });

        Map<String, Object> missing = nodes.executeApproved(state("NOT_REQUIRED", "APPROVED", SUBJECT));
        Map<String, Object> denied = nodes.executeApproved(state("PENDING", "DENIED", SUBJECT));

        assertThat(executions).hasValue(0);
        assertThat(missing.get(RetailState.ROUTE)).isEqualTo("finish");
        assertThat(denied).containsEntry(RetailState.APPROVAL_STATUS, "DENIED");
    }

    @Test
    void approvalMustBeAttributedToTheThreadOwner() {
        AtomicInteger executions = new AtomicInteger();
        RetailGraphNodes nodes = nodes(EXTERNAL_ACTION, invocation -> {
            executions.incrementAndGet();
            return new ActionOutcome(invocation.invocationId(), ActionOutcome.Status.SUCCEEDED,
                    "OK", "published", List.of(), Instant.now(), true);
        });

        Map<String, Object> update = nodes.executeApproved(state("PENDING", "APPROVED", "different-subject"));

        assertThat(executions).hasValue(0);
        assertThat(update.get(RetailState.ROUTE)).isEqualTo("finish");
    }

    @Test
    void defaultRegistryAllowsLowRiskReadOnlyActionsWithoutApproval() {
        RetailActionRegistry registry = new RetailActionRegistry();

        assertThat(registry.find("search_catalog")).isPresent()
                .get().extracting(ActionDefinition::requiresHumanApproval).isEqualTo(false);
        assertThat(registry.find("get_history")).isPresent()
                .get().extracting(ActionDefinition::requiresHumanApproval).isEqualTo(false);
        assertThat(registry.find("check_stock")).isPresent()
                .get().extracting(ActionDefinition::requiresHumanApproval).isEqualTo(false);
    }

    @Test
    void executionRechecksRegistryInsteadOfTrustingStaleAuthorizationState() {
        AtomicInteger executions = new AtomicInteger();
        ActionRegistry currentRegistry = registry(EXTERNAL_ACTION);
        RetailGraphNodes nodes = nodes(currentRegistry, invocation -> {
            executions.incrementAndGet();
            return new ActionOutcome(invocation.invocationId(), ActionOutcome.Status.SUCCEEDED,
                    "OK", "must not run", List.of(), Instant.now(), true);
        });

        Map<String, Object> update = nodes.execute(state(false, "NOT_REQUIRED", "", ""));

        assertThat(executions).hasValue(0);
        assertThat(update.get(RetailState.ROUTE)).isEqualTo("finish");
        assertThat(update.get(RetailState.FEEDBACK).toString()).contains("requires explicit approval");
    }

    @Test
    void simulatorGatewayIndependentlyRejectsApprovalRequiredActions() {
        ProductCatalog catalog = new ProductCatalog();
        RetailActionGateway gateway = new RetailActionGateway(registry(EXTERNAL_ACTION), catalog, new OrderHistory());
        var invocation = new com.example.retail.agent.domain.ActionInvocation(
                "inv-1", EXTERNAL_ACTION.id(), SUBJECT, Map.of(), "idem-1", Instant.now(), "int-1", 0);

        ActionOutcome outcome = gateway.execute(invocation);

        assertThat(outcome.status()).isEqualTo(ActionOutcome.Status.REJECTED);
        assertThat(outcome.resultCode()).isEqualTo("APPROVAL_REQUIRED");
    }

    private static RetailGraphNodes nodes(ActionDefinition definition, ActionGateway gateway) {
        return nodes(registry(definition), gateway);
    }

    private static RetailGraphNodes nodes(ActionRegistry registry, ActionGateway gateway) {
        ProductCatalog catalog = new ProductCatalog();
        var vectorStore = AgentConfigurationForTests.vectorStore(catalog);
        LlmClient llm = new LlmClient((task, system, user) -> CompletableFuture.completedFuture("{}"));
        return new RetailGraphNodes(llm, vectorStore, catalog, new OrderHistory(), new EpisodicMemory(),
                new AgentSettings(3, 0.4, 4, 3, 3, 8, 2, 180, 260), registry, gateway);
    }

    private static ActionRegistry registry(ActionDefinition definition) {
        return new ActionRegistry() {
            @Override public Optional<ActionDefinition> find(String id) {
                return definition.id().equals(id) ? Optional.of(definition) : Optional.empty();
            }
            @Override public java.util.Collection<ActionDefinition> availableTo(String subjectId) {
                return List.of(definition);
            }
        };
    }

    private static RetailState state(String status, String decision, String actor) {
        return state(true, status, decision, actor);
    }

    private static RetailState state(boolean approvalRequired, String status, String decision, String actor) {
        IntentionRecord intention = IntentionRecord.start("publish this result");
        PendingActionApproval request = new PendingActionApproval("approval-test-token", EXTERNAL_ACTION.id(),
                EXTERNAL_ACTION.description(), EXTERNAL_ACTION.kind(), EXTERNAL_ACTION.risk(),
                EXTERNAL_ACTION.effects(), EXTERNAL_ACTION.destructive(), EXTERNAL_ACTION.irreversible(),
                EXTERNAL_ACTION.financial(), EXTERNAL_ACTION.externalSharing(), Map.of(), "candidate", SUBJECT,
                "intention:" + sha256(intention.committedGoal()), intention.revision(), Instant.now());
        return new RetailState(Map.<String, Object>ofEntries(
                Map.entry(RetailState.THREAD_ID, "thread-42"),
                Map.entry(RetailState.QUERY, "publish this result"),
                Map.entry(RetailState.CUSTOMER, SUBJECT),
                Map.entry(RetailState.ITERATION, 0),
                Map.entry(RetailState.PLANNING_ROUND, 0),
                Map.entry(RetailState.FAILURES, 0),
                Map.entry(RetailState.SELECTED, new Proposal(EXTERNAL_ACTION.id(), "", "candidate", 0.8, 0.9)),
                Map.entry(RetailState.APPROVAL_REQUIRED, approvalRequired),
                Map.entry(RetailState.APPROVAL_STATUS, status),
                Map.entry(RetailState.APPROVAL_REQUEST, request),
                Map.entry(RetailState.APPROVAL_DECISION_ID, "PENDING".equals(status) && !decision.isBlank() ? request.approvalId() : ""),
                Map.entry(RetailState.APPROVAL_DECISION, decision),
                Map.entry(RetailState.APPROVAL_ACTOR, actor),
                Map.entry(RetailState.INTENTION, intention),
                Map.entry(RetailState.BUDGET, ResourceBudget.start(3, 3, 8))));
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Keeps test construction independent from Spring application wiring. */
    private static final class AgentConfigurationForTests {
        static org.springframework.ai.vectorstore.VectorStore vectorStore(ProductCatalog catalog) {
            return com.example.retail.config.AgentConfiguration.indexedVectorStore(new HashEmbeddingModel(), catalog);
        }
    }
}
