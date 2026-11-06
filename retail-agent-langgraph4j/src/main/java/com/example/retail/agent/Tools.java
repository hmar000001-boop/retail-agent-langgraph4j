package com.example.retail.agent;

import com.example.retail.agent.domain.ActionDefinition;
import com.example.retail.domain.OrderHistory;
import com.example.retail.domain.Product;
import com.example.retail.domain.ProductCatalog;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * CoALA grounding actions. The action space is a closed whitelist; the LLM only proposes, this class authorizes.
 * get_history ALWAYS uses the authenticated customer, whatever the model passes as args.
 */
public class Tools {
    public static final Set<String> ALLOWED = Set.of("search_catalog", "get_history", "check_stock", "finish");
    private static final Pattern FIRST_PRODUCT = Pattern.compile("\\[search_catalog][^\\n]*?(p-\\d+)");

    private final ProductCatalog catalog;
    private final OrderHistory orders;

    public Tools(ProductCatalog catalog, OrderHistory orders) {
        this.catalog = catalog;
        this.orders = orders;
    }

    /** Closed action policy. Unknown actions fail closed; finish is a control proposal, not a tool. */
    public static boolean requiresHumanApproval(String actionId) {
        ActionDefinition definition = switch (actionId) {
            case "search_catalog", "get_history", "check_stock" -> new ActionDefinition(
                    actionId, "Read-only local retail lookup", ActionDefinition.ActionKind.INTERNAL,
                    ActionDefinition.RiskLevel.LOW, List.of(ActionDefinition.Effect.READ), List.of(),
                    java.time.Duration.ofSeconds(5), true, false, false, false, false, false);
            case "finish" -> null;
            default -> throw new IllegalArgumentException("action is not registered: " + actionId);
        };
        return definition != null && definition.requiresHumanApproval();
    }

    public String run(Proposal p, String customerId) {
        return switch (p.tool()) {
            case "search_catalog" -> search(p.args());
            case "get_history" -> history(customerId);
            case "check_stock" -> stock(p.args().trim());
            default -> "[error] unsupported tool " + p.tool();
        };
    }

    private String search(String query) {
        String items = catalog.search(query, 3).stream()
                .map(pr -> pr.id() + "|" + pr.name() + "|" + pr.price() + "|" + pr.description())
                .collect(Collectors.joining("; "));
        return "[search_catalog] \"" + query + "\" -> " + (items.isEmpty() ? "no results" : items);
    }

    private String history(String customerId) {
        String names = orders.productIds(customerId).stream()
                .map(catalog::byId)
                .flatMap(java.util.Optional::stream)
                .map(pr -> pr.id() + " " + pr.name())
                .collect(Collectors.joining("; "));
        return "[get_history] " + customerId + " bought: " + (names.isEmpty() ? "nothing yet" : names);
    }

    private String stock(String productId) {
        return catalog.byId(productId)
                .map((Product pr) -> "[check_stock] " + pr.id() + " stock=" + pr.stock() + " price=" + pr.price())
                .orElse("[check_stock] unknown product id '" + productId + "'");
    }

    /** First product id in the most recent search observation, or "". */
    public static String firstProductId(List<String> observations) {
        for (int i = observations.size() - 1; i >= 0; i--) {
            Matcher m = FIRST_PRODUCT.matcher(observations.get(i));
            if (m.find()) return m.group(1);
        }
        return "";
    }
}
