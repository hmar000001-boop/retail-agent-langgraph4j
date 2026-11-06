package com.example.retail.agent;

import com.example.retail.agent.domain.ActionDefinition;
import com.example.retail.agent.domain.ActionInvocation;
import com.example.retail.agent.domain.ActionOutcome;
import com.example.retail.agent.port.ActionGateway;
import com.example.retail.agent.port.ActionRegistry;
import com.example.retail.domain.OrderHistory;
import com.example.retail.domain.Product;
import com.example.retail.domain.ProductCatalog;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * In-process idempotent adapter for the deterministic retail simulator. The action registry is
 * checked again at execution time; unknown actions never fall through to a generic tool handler.
 */
public final class RetailActionGateway implements ActionGateway {
    private final ActionRegistry registry;
    private final ProductCatalog catalog;
    private final OrderHistory orders;
    private final ConcurrentMap<String, IdempotentResult> outcomes = new ConcurrentHashMap<>();

    public RetailActionGateway(ActionRegistry registry, ProductCatalog catalog, OrderHistory orders) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.orders = Objects.requireNonNull(orders, "orders");
    }

    @Override public ActionOutcome execute(ActionInvocation invocation) {
        Objects.requireNonNull(invocation, "invocation");
        ActionDefinition definition = registry.find(invocation.actionId()).orElse(null);
        if (definition == null) return outcome(invocation, ActionOutcome.Status.REJECTED, "UNKNOWN_ACTION",
                "Action is not registered: " + invocation.actionId());
        // Revalidate authority before looking at idempotency state. A prior cached result must never
        // turn a currently approval-required action into an executable action.
        if (definition.requiresHumanApproval()) return outcome(invocation, ActionOutcome.Status.REJECTED,
                "APPROVAL_REQUIRED", "Action requires an explicit approval decision");
        String requestFingerprint = requestFingerprint(invocation);
        IdempotentResult stored = outcomes.compute(invocation.idempotencyKey(), (key, existing) -> {
            if (existing != null) {
                if (!existing.requestFingerprint().equals(requestFingerprint)) {
                    throw new IdempotencyConflictException("idempotency key was already used for a different action request");
                }
                return existing;
            }
            return new IdempotentResult(requestFingerprint, executeOnce(invocation));
        });
        ActionOutcome original = stored.outcome();
        // Preserve the logical result but bind it to the current invocation identity. The effect
        // is executed once; an idempotent retry must not receive a stale invocationId.
        return new ActionOutcome(invocation.invocationId(), original.status(), original.resultCode(),
                original.summary(), original.producedEvidenceIds(), original.completedAt(), original.effectsVerified());
    }

    private ActionOutcome executeOnce(ActionInvocation invocation) {
        try {
            String result = switch (invocation.actionId()) {
                case "search_catalog" -> search(requiredArg(invocation, "query"));
                case "get_history" -> history(invocation.subjectId());
                case "check_stock" -> stock(requiredArg(invocation, "productId"));
                default -> null;
            };
            if (result == null) return outcome(invocation, ActionOutcome.Status.REJECTED,
                    "UNSUPPORTED_ACTION", "No executor is registered for this action");
            if (result.startsWith("[error]") || result.contains("unknown product id")) {
                return outcome(invocation, ActionOutcome.Status.FAILED, "ACTION_FAILED", result);
            }
            return outcome(invocation, ActionOutcome.Status.SUCCEEDED, "OK", result);
        } catch (IllegalArgumentException e) {
            return outcome(invocation, ActionOutcome.Status.REJECTED, "INVALID_ARGUMENT", e.getMessage());
        } catch (RuntimeException e) {
            return outcome(invocation, ActionOutcome.Status.FAILED, "EXECUTION_ERROR",
                    e.getClass().getSimpleName() + ": " + Objects.toString(e.getMessage(), "unexpected failure"));
        }
    }

    private String search(String query) {
        String items = catalog.search(query, 3).stream()
                .map(pr -> pr.id() + "|" + pr.name() + "|" + pr.price() + "|" + pr.description())
                .collect(Collectors.joining("; "));
        return "[search_catalog] \"" + query + "\" -> " + (items.isEmpty() ? "no results" : items);
    }

    private String history(String authenticatedSubject) {
        String names = orders.productIds(authenticatedSubject).stream().map(catalog::byId)
                .flatMap(java.util.Optional::stream).map(pr -> pr.id() + " " + pr.name())
                .collect(Collectors.joining("; "));
        return "[get_history] " + authenticatedSubject + " bought: " + (names.isEmpty() ? "nothing yet" : names);
    }

    private String stock(String productId) {
        return catalog.byId(productId)
                .map((Product pr) -> "[check_stock] " + pr.id() + " stock=" + pr.stock() + " price=" + pr.price())
                .orElse("[check_stock] unknown product id '" + productId + "'");
    }

    private static String requiredArg(ActionInvocation invocation, String key) {
        String value = invocation.arguments().get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing required argument: " + key);
        return value.trim();
    }

    private static String requestFingerprint(ActionInvocation invocation) {
        StringBuilder canonical = new StringBuilder()
                .append(invocation.actionId().length()).append(':').append(invocation.actionId())
                .append(invocation.subjectId().length()).append(':').append(invocation.subjectId());
        new java.util.TreeMap<>(invocation.arguments()).forEach((key, value) -> canonical
                .append(key.length()).append(':').append(key).append(value.length()).append(':').append(value));
        canonical.append(invocation.intentionId().length()).append(':').append(invocation.intentionId())
                .append(':').append(invocation.expectedIntentionRevision());
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }

    private static ActionOutcome outcome(ActionInvocation invocation, ActionOutcome.Status status,
                                         String code, String summary) {
        return new ActionOutcome(invocation.invocationId(), status, code, summary,
                java.util.List.of(), Instant.now(), false);
    }

    private record IdempotentResult(String requestFingerprint, ActionOutcome outcome) { }

    private static final class IdempotencyConflictException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private IdempotencyConflictException(String message) { super(message); }
    }
}
