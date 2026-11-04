package com.example.retail.llm;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;

/** Flattens a Spring AI Prompt to text and extracts the [TASK:X] tag every agent prompt carries. */
public final class PromptText {
    private static final Pattern TASK = Pattern.compile("\\[TASK:([A-Z_]+)]");

    private PromptText() {}

    public static String of(Prompt prompt) {
        return prompt.getInstructions().stream().map(Message::getText).collect(Collectors.joining("\n"));
    }

    public static String task(String promptText) {
        Matcher m = TASK.matcher(promptText);
        return m.find() ? m.group(1) : "UNKNOWN";
    }
}
