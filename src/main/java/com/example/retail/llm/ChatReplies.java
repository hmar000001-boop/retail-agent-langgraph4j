package com.example.retail.llm;

import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/** Single place that builds / reads ChatResponse objects (the only spot to touch if the Spring AI API shifts). */
public final class ChatReplies {
    private ChatReplies() {}

    public static ChatResponse of(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    public static String textOf(ChatResponse response) {
        String t = response.getResult().getOutput().getText();
        return t == null ? "" : t;
    }
}
