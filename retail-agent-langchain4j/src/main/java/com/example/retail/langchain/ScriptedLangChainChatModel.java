package com.example.retail.langchain;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic ChatModel for orchestration tests. It scripts the proposal and answer text only.
 * It does not evaluate candidates, authorize tools, determine goal completion, or set runtime state.
 */
public final class ScriptedLangChainChatModel implements ChatModel {
    private static final Pattern FIRST_PRODUCT = Pattern.compile("\\[search_catalog]\\s+.*?(p-\\d+)\\|");
    private static final Pattern VERIFIED = Pattern.compile("(p-\\d+)\\|([^|]+)\\|([0-9]+(?:\\.[0-9]+)?)\\|(\\d+)");
    private final AtomicInteger calls = new AtomicInteger();
    private final boolean alwaysProposePrematureFinish;

    public ScriptedLangChainChatModel() {
        this(false);
    }

    /** Adversarial mode used to prove the runtime rejects an unsupported finish proposal. */
    public ScriptedLangChainChatModel(boolean alwaysProposePrematureFinish) {
        this.alwaysProposePrematureFinish = alwaysProposePrematureFinish;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        calls.incrementAndGet();
        String prompt = request.messages().toString();
        String result;
        if (prompt.contains("[TASK:PROPOSE]")) result = propose(prompt, alwaysProposePrematureFinish);
        else if (prompt.contains("[TASK:REFLECT]")) result = reflect(prompt);
        else if (prompt.contains("[TASK:ANSWER]")) result = answer(prompt);
        else result = "";
        return ChatResponse.builder().aiMessage(AiMessage.from(result)).build();
    }

    public int calls() { return calls.get(); }

    private static String propose(String prompt, boolean alwaysPrematureFinish) {
        if (alwaysPrematureFinish) return "finish||claim completion before verifying product evidence|0.99";
        String observations = section(prompt, "Observations so far:", "Bounded workspace:");
        String query = lineAfter(prompt, "Committed customer goal:");
        if (!observations.contains("[search_catalog]")) {
            return "get_history||recall prior purchases|0.55\n"
                    + "search_catalog|" + query.replace('|', '/') + "|retrieve matching catalogue candidates|0.91";
        }
        if (!observations.contains("[check_stock]")) {
            Matcher matcher = FIRST_PRODUCT.matcher(observations);
            String id = matcher.find() ? matcher.group(1) : "p-103";
            return "check_stock|" + id + "|verify live catalogue stock and price|0.88\n"
                    + "search_catalog|" + query + " alternatives|look for alternatives|0.35";
        }
        return "finish||request that the runtime check whether the goal is satisfied|0.99";
    }

    private static String reflect(String prompt) {
        String observations = section(prompt, "Observations:", "Latest observation:");
        boolean hasStock = observations.contains("[check_stock]");
        return hasStock
                ? "{\"goalSatisfied\":true,\"summary\":\"stock and price verification observed\"}"
                : "{\"goalSatisfied\":false,\"summary\":\"candidate search completed; stock verification is still needed\"}";
    }

    private static String answer(String prompt) {
        String products = section(prompt, "Verified current products (authoritative):", "Clarification or failure note:");
        Matcher matcher = VERIFIED.matcher(products);
        if (!matcher.find()) return "No product could be verified against current catalogue evidence.";
        String id = matcher.group(1);
        String name = matcher.group(2).trim();
        String price = matcher.group(3);
        String stock = matcher.group(4);
        return String.format(Locale.ROOT, "I recommend %s (%s) at $%s. It is currently in stock (%s units).", name, id, price, stock);
    }

    private static String lineAfter(String text, String label) {
        int start = text.indexOf(label);
        if (start < 0) return "";
        start += label.length();
        int end = text.indexOf('\n', start);
        return text.substring(start, end < 0 ? text.length() : end).trim();
    }

    private static String section(String text, String startLabel, String endLabel) {
        int start = text.indexOf(startLabel);
        if (start < 0) return "";
        start += startLabel.length();
        int end = text.indexOf(endLabel, start);
        return text.substring(start, end < 0 ? text.length() : end).trim();
    }
}
