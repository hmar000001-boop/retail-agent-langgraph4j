package com.example.retail.llm;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Predicate;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/** Simulation strategy 1: deterministic rules matched against the prompt. First matching rule wins. */
public class ScriptedChatModel implements ChatModel {

    /** @param task the [TASK:X] tag this rule applies to, or "*" */
    public record Rule(String task, Predicate<String> when, Function<String, String> then) {}

    private final List<Rule> rules;
    private final List<String> seen = new CopyOnWriteArrayList<>();

    public ScriptedChatModel(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String text = PromptText.of(prompt);
        seen.add(text);
        String task = PromptText.task(text);
        for (Rule r : rules) {
            if (("*".equals(r.task()) || r.task().equals(task)) && r.when().test(text)) {
                return ChatReplies.of(r.then().apply(text));
            }
        }
        throw new NoScriptedResponseException(task, text);
    }

    /** Every prompt received, for assertions. */
    public List<String> seenPrompts() {
        return List.copyOf(seen);
    }
}
