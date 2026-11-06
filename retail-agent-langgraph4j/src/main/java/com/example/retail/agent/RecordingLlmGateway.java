package com.example.retail.agent;

import com.example.retail.llm.CassetteStore;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Opt-in recorder for the native LangChain4j path; the cassette payload is never logged to stdout. */
public final class RecordingLlmGateway implements LlmGateway {
    private final LlmGateway delegate;
    private final CassetteStore store;

    public RecordingLlmGateway(LlmGateway delegate, CassetteStore store) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public CompletableFuture<String> complete(String task, String system, String user) {
        String prompt = Objects.requireNonNullElse(system, "") + "\n[TASK:" + task + "]\n"
                + Objects.requireNonNullElse(user, "");
        return delegate.complete(task, system, user).thenApply(response -> {
            store.write(prompt, Objects.requireNonNullElse(response, ""));
            return response;
        });
    }
}
