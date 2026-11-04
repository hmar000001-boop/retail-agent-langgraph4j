package com.example.retail.llm;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/** Simulation strategy 3: decorator that can wrap ANY ChatModel (scripted, replay or real). */
public class FaultInjectingChatModel implements ChatModel {
    private static final Pattern CONFIDENCE = Pattern.compile("(\"confidence\"\\s*:\\s*)[0-9.]+");
    private static final Pattern SCORES = Pattern.compile("(\"scores\"\\s*:\\s*\\[)([^\\]]*)(])");

    private final ChatModel delegate;
    private final List<FaultRule> rules;
    private final AtomicInteger[] counters;

    public FaultInjectingChatModel(ChatModel delegate, List<FaultRule> rules) {
        this.delegate = delegate;
        this.rules = List.copyOf(rules);
        this.counters = new AtomicInteger[rules.size()];
        for (int i = 0; i < counters.length; i++) counters[i] = new AtomicInteger();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String task = PromptText.task(PromptText.of(prompt));
        for (int i = 0; i < rules.size(); i++) {
            FaultRule r = rules.get(i);
            boolean applies = r.task() == null || "*".equals(r.task()) || r.task().equals(task);
            if (!applies) continue;
            int n = counters[i].getAndIncrement();
            if (n >= r.skip() && n < r.skip() + r.times()) {
                return inject(r.kind(), prompt, task);
            }
        }
        return delegate.call(prompt);
    }

    private ChatResponse inject(FaultKind kind, Prompt prompt, String task) {
        return switch (kind) {
            case TIMEOUT -> throw new LlmTimeoutException("injected timeout in " + task);
            case MALFORMED_JSON -> ChatReplies.of("Sure! Here is my answer: {not valid json");
            case EMPTY -> ChatReplies.of("");
            case LOW_CONFIDENCE -> ChatReplies.of(lowConfidence(ChatReplies.textOf(delegate.call(prompt))));
        };
    }

    static String lowConfidence(String json) {
        String out = CONFIDENCE.matcher(json).replaceAll(mr -> Matcher.quoteReplacement(mr.group(1) + "0.1"));
        Matcher m = SCORES.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String low = m.group(2).replaceAll("[0-9]*\\.?[0-9]+", "0.1");
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + low + m.group(3)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
