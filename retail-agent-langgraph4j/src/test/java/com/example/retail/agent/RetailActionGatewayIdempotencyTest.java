package com.example.retail.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.retail.agent.domain.ActionDefinition;
import com.example.retail.agent.domain.ActionInvocation;
import com.example.retail.agent.port.ActionRegistry;
import com.example.retail.domain.OrderHistory;
import com.example.retail.domain.ProductCatalog;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RetailActionGatewayIdempotencyTest {
    private static final ActionDefinition SEARCH = new ActionDefinition("search_catalog", "Search local catalogue",
            ActionDefinition.ActionKind.INTERNAL, ActionDefinition.RiskLevel.LOW,
            List.of(ActionDefinition.Effect.READ), List.of(), Duration.ofSeconds(5), true,
            false, false, false, false, false);

    @Test
    void sameRequestAndKeyReturnsCachedResultWithCurrentInvocationId() {
        RetailActionGateway gateway = gateway();
        ActionInvocation first = invocation("invoke-1", "trail shoes", "idem-1");
        ActionInvocation retry = invocation("invoke-2", "trail shoes", "idem-1");

        var firstResult = gateway.execute(first);
        var retryResult = gateway.execute(retry);

        assertThat(firstResult.status()).isEqualTo(retryResult.status());
        assertThat(firstResult.summary()).isEqualTo(retryResult.summary());
        assertThat(firstResult.invocationId()).isEqualTo("invoke-1");
        assertThat(retryResult.invocationId()).isEqualTo("invoke-2");
    }

    @Test
    void reusingKeyForDifferentPayloadIsRejected() {
        RetailActionGateway gateway = gateway();
        gateway.execute(invocation("invoke-1", "trail shoes", "idem-2"));

        assertThatThrownBy(() -> gateway.execute(invocation("invoke-2", "formal shirt", "idem-2")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different action request");
    }

    private static RetailActionGateway gateway() {
        ActionRegistry registry = new ActionRegistry() {
            @Override public Optional<ActionDefinition> find(String actionId) {
                return SEARCH.id().equals(actionId) ? Optional.of(SEARCH) : Optional.empty();
            }
            @Override public java.util.Collection<ActionDefinition> availableTo(String subjectId) {
                return List.of(SEARCH);
            }
        };
        return new RetailActionGateway(registry, new ProductCatalog(), new OrderHistory());
    }

    private static ActionInvocation invocation(String id, String query, String idempotencyKey) {
        return new ActionInvocation(id, "search_catalog", "subject-3", java.util.Map.of("query", query),
                idempotencyKey, Instant.parse("2026-11-06T12:00:00Z"), "intention-1", 4);
    }
}
