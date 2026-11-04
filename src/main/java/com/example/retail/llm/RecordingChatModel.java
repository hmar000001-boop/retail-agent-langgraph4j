package com.example.retail.llm;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/** Simulation strategy 2a: wraps the real model and saves every exchange as a cassette. */
public class RecordingChatModel implements ChatModel {
    private final ChatModel delegate;
    private final CassetteStore store;

    public RecordingChatModel(ChatModel delegate, CassetteStore store) {
        this.delegate = delegate;
        this.store = store;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ChatResponse response = delegate.call(prompt);
        store.write(PromptText.of(prompt), ChatReplies.textOf(response));
        return response;
    }
}
