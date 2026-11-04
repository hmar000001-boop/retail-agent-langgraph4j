package com.example.retail.llm;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/** Simulation strategy 2b: serves recorded responses; a missing cassette fails loudly. No network, no key. */
public class ReplayChatModel implements ChatModel {
    private final CassetteStore store;

    public ReplayChatModel(CassetteStore store) {
        this.store = store;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String text = PromptText.of(prompt);
        return store.read(text)
                .map(ChatReplies::of)
                .orElseThrow(() -> new CassetteMissException(PromptText.task(text), CassetteStore.key(text)));
    }
}
