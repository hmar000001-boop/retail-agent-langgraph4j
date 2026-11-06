package com.example.retail.agent;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

/**
 * Compatibility adapter for the deterministic Spring AI scripted/replay/fault-injection test stack.
 * Production model calls use the native LangChain4j gateway.
 */
public final class SpringAiLlmGateway implements LlmGateway {
    private final ChatClient client;
    private final Executor executor;

    public SpringAiLlmGateway(ChatModel model, Executor executor) {
        this.client = ChatClient.create(Objects.requireNonNull(model, "model"));
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public CompletableFuture<String> complete(String task, String system, String user) {
        return CompletableFuture.supplyAsync(() -> {
            String content = client.prompt().system(system).user("[TASK:" + task + "]\n" + user)
                    .call().content();
            return content == null ? "" : content;
        }, executor);
    }
}
