package com.example.retail.llm;

import com.example.retail.llm.ScriptedChatModel.Rule;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default script for the retail agent. It reads the same structured prompt sections a real model sees
 * (QUERY / CUSTOMER / OBSERVATIONS / PROPOSALS) and answers like a sensible planner would.
 * Add or prepend rules in tests to script other scenarios.
 */
public final class RetailScriptedResponder {
    private static final Pattern QUERY = Pattern.compile("QUERY: (.*)");
    private static final Pattern CUSTOMER = Pattern.compile("CUSTOMER: (.*)");
    private static final Pattern OBS = Pattern.compile("(?s)OBSERVATIONS:\\n(.*?)(?:\\nPROPOSALS:|$)");
    private static final Pattern CONF = Pattern.compile("conf=([0-9.]+)");
    private static final Pattern FIRST_PRODUCT = Pattern.compile("\\[search_catalog][^\\n]*?(p-\\d+)");
    private static final Pattern STOCK = Pattern.compile("\\[check_stock] (p-\\d+) stock=(\\d+) price=([0-9.]+)");

    private RetailScriptedResponder() {}

    public static List<Rule> rules() {
        List<Rule> r = new ArrayList<>();

        // PROPOSE: nothing found yet -> look at history and search the catalog
        r.add(new Rule("PROPOSE", t -> !obs(t).contains("[search_catalog]"), t -> proposals(
                p("get_history", find(CUSTOMER, t), "recall what this customer bought before", 0.6),
                p("search_catalog", find(QUERY, t), "find products matching the request", 0.9))));
        // PROPOSE: candidates found, not verified -> check stock and price of the best one
        r.add(new Rule("PROPOSE", t -> !obs(t).contains("[check_stock]"), t -> proposals(
                p("check_stock", firstProduct(obs(t)), "verify availability and price", 0.9),
                p("search_catalog", find(QUERY, t) + " alternatives", "widen the search", 0.3))));
        // PROPOSE: verified -> done
        r.add(new Rule("PROPOSE", t -> true, t -> proposals(p("finish", "", "enough verified information", 0.95))));

        // EVALUATE: score = the proposer's own confidence
        r.add(new Rule("EVALUATE", t -> true, t -> {
            List<String> scores = new ArrayList<>();
            Matcher m = CONF.matcher(t);
            while (m.find()) scores.add(m.group(1));
            return "{\"scores\":[" + String.join(",", scores) + "]}";
        }));

        // REFLECT: satisfied once a stock check is in the observations
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
        if (!s.find()) return "I found some candidates but could not verify availability yet.";
        String id = s.group(1);
        int stock = Integer.parseInt(s.group(2));
        String name = nameOf(obs, id);
        return stock > 0
                ? String.format(Locale.ROOT, "I recommend %s (%s) at $%s. It is in stock (%d units).", name, id, s.group(3), stock)
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
        Matcher m = FIRST_PRODUCT.matcher(obs);
        return m.find() ? m.group(1) : "";
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
