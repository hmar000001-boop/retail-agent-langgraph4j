package com.example.retail.agent;

import java.util.concurrent.CompletableFuture;

/** Provider-neutral asynchronous LLM boundary used by the LangGraph4j application layer. */
public interface LlmGateway {
    CompletableFuture<String> complete(String task, String system, String user);
}
