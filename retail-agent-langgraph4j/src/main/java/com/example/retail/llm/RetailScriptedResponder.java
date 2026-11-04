package com.example.retail.llm;

import com.example.retail.llm.ScriptedChatModel.Rule;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic scripted model for orchestration tests. It produces proposals and summaries only;
 * candidate evaluation and goal verification are performed independently by shared-core Java policies.
 * Add or prepend rules in tests to script other scenarios.
 */
public final class RetailScriptedResponder {
    private static final Pattern QUERY = Pattern.compile("QUERY: (.*)");
    private static final Pattern CUSTOMER = Pattern.compile("CUSTOMER: (.*)");
    private static final Pattern OBS = Pattern.compile("(?s)OBSERVATIONS:\\n(.*?)(?:\\nPROPOSALS:|$)");
    private static final Pattern FIRST_PRODUCT = Pattern.compile("\\[search_catalog][^\\n]*?(p-\\d+)");
    private static final Pattern STOCK = Pattern.compile("\\[check_stock] (p-\\d+) stock=(\\d+) price=([0-9.]+)");

    private RetailScriptedResponder() {}

    public static List<Rule> rules() {
        List<Rule> r = new ArrayList<>();

        // PROPOSE: nothing found yet -> look at history and search the catalog
        r.add(new Rule("PROPOSE", t -> !obs(t).contains("[search_catalog]"), t -> proposals(
                p("get_history", find(CUSTOMER, t), "recall what this customer bought before", 0.6),
                p("search_catalog", find(QUERY, t), "find products matching the request", 0.9))));
        // PROPOSE: the newest search result has not been verified yet
        r.add(new Rule("PROPOSE", t -> {
            String id = firstProduct(obs(t));
            return !id.isBlank() && !stockCheckedFor(obs(t), id);
        }, t -> proposals(p("check_stock", firstProduct(obs(t)), "verify the newest candidate's current availability and price", 0.9))));
        // PROPOSE: a checked candidate did not satisfy the full request; broaden/revise retrieval rather than claiming success
        r.add(new Rule("PROPOSE", t -> !hasMatchingStockObservation(t), t -> proposals(
                p("search_catalog", find(QUERY, t) + " alternatives", "retrieve a different candidate after the previous evidence failed a constraint", 0.8))));
        // PROPOSE: verified -> done (still only a proposal; the Java verifier decides)
        r.add(new Rule("PROPOSE", t -> true, t -> proposals(p("finish", "", "request completion after matching stock evidence", 0.95))));

        // REFLECT: a natural-language/structured interpretation only; runtime evidence verification is authoritative
        r.add(new Rule("REFLECT", t -> obs(t).contains("[check_stock]"),
                t -> "{\"goalSatisfied\":true,\"summary\":\"stock and price verified\"}"));
        r.add(new Rule("REFLECT", t -> true,
                t -> "{\"goalSatisfied\":false,\"summary\":\"need to verify stock and price of the top candidate\"}"));

        // ANSWER: grounded in the observations only
        r.add(new Rule("ANSWER", t -> true, RetailScriptedResponder::answer));
        return r;
    }

    private static String answer(String t) {
        String obs = obs(t);
        Matcher s = STOCK.matcher(obs);
        String id = null;
        String stockText = null;
        String price = null;
        while (s.find()) {
            id = s.group(1);
            stockText = s.group(2);
            price = s.group(3);
        }
        if (id == null) return "I found some candidates but could not verify availability yet.";
        int stock = Integer.parseInt(stockText);
        String name = nameOf(obs, id);
        return stock > 0
                ? String.format(Locale.ROOT, "I recommend %s (%s) at $%s. It is in stock (%d units).", name, id, price, stock)
                : String.format(Locale.ROOT, "%s (%s) is currently out of stock.", name, id);
    }

    private static String nameOf(String obs, String id) {
        Matcher m = Pattern.compile(Pattern.quote(id) + "\\|([^|;]+)\\|").matcher(obs);
        return m.find() ? m.group(1) : id;
    }

    private static String obs(String t) {
        Matcher m = OBS.matcher(t);
        return m.find() ? m.group(1) : "";
    }

    private static String find(Pattern p, String t) {
        Matcher m = p.matcher(t);
        return m.find() ? m.group(1).trim() : "";
    }

    private static String firstProduct(String obs) {
        String[] observations = obs.split("\\R");
        for (int i = observations.length - 1; i >= 0; i--) {
            if (!observations[i].startsWith("[search_catalog]")) continue;
            Matcher m = FIRST_PRODUCT.matcher(observations[i]);
            if (m.find()) return m.group(1);
        }
        return "";
    }

    private static boolean stockCheckedFor(String observations, String id) {
        return Pattern.compile("\\[check_stock]\\s+" + Pattern.quote(id) + "\\s+stock=").matcher(observations).find();
    }

    private static boolean hasMatchingStockObservation(String task) {
        String query = find(QUERY, task).toLowerCase(Locale.ROOT);
        String observations = obs(task);
        Matcher stock = STOCK.matcher(observations);
        while (stock.find()) {
            String id = stock.group(1);
            int count = Integer.parseInt(stock.group(2));
            double price = Double.parseDouble(stock.group(3));
            String info = productInfo(observations, id).toLowerCase(Locale.ROOT);
            if (count <= 0 || price > priceLimit(query)) continue;
            if ((query.contains("waterproof") || query.contains("gore-tex") || query.contains(" gtx"))
                    && !(info.contains("waterproof") || info.contains("gore-tex") || info.contains("gtx"))) continue;
            if ((query.contains("trail") || query.contains("rocky")) && !info.contains("trail")) continue;
            if (query.contains("running") && !info.contains("running") && !info.contains("run")) continue;
            if (query.contains("shoe") && !info.contains("shoe") && !info.contains("footwear")) continue;
            return true;
        }
        return false;
    }

    private static String productInfo(String observations, String id) {
        Matcher matcher = Pattern.compile(Pattern.quote(id) + "\\|([^;\\n]+)").matcher(observations);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static double priceLimit(String query) {
        Matcher matcher = Pattern.compile("(?i)(?:under|below|less than|up to|maximum|max(?:imum)?(?: of)?)\\s*\\$?\\s*(\\d+(?:\\.\\d{1,2})?)").matcher(query);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : Double.POSITIVE_INFINITY;
    }

    private static String p(String tool, String args, String why, double conf) {
        return String.format(Locale.ROOT, "{\"tool\":\"%s\",\"args\":\"%s\",\"rationale\":\"%s\",\"confidence\":%.2f}",
                tool, esc(args), esc(why), conf);
    }

    private static String proposals(String... items) {
        return "{\"proposals\":[" + String.join(",", items) + "]}";
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
