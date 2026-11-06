package com.example.retail.agent;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Native LangChain4j ChatModel adapter. Remote providers use their true async transport when supported. */
public final class LangChain4jLlmGateway implements LlmGateway {
    private final ChatModel model;

    public LangChain4jLlmGateway(ChatModel model) {
        this.model = Objects.requireNonNull(model, "model");
    }

    @Override
    public CompletableFuture<String> complete(String task, String system, String user) {
        ChatRequest request = ChatRequest.builder()
                .messages(SystemMessage.from(system), UserMessage.from("[TASK:" + task + "]\n" + user))
                .build();
        return model.chatAsync(request).thenApply(response -> {
            String text = response.aiMessage().text();
            return text == null ? "" : text;
        });
    }
}
