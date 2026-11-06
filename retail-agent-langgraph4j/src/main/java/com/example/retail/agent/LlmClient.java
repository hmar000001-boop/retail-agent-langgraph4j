package com.example.retail.agent;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.json.JsonMapper;

/** Typed parsing and one bounded repair attempt over a provider-neutral asynchronous model gateway. */
public final class LlmClient {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final LlmGateway gateway;
    private final AtomicInteger retries = new AtomicInteger();
    private final AtomicInteger calls = new AtomicInteger();

    public LlmClient(LlmGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    /** Compatibility constructor retained for existing scripted/replay tests. */
    public LlmClient(org.springframework.ai.chat.model.ChatModel model) {
        this(new SpringAiLlmGateway(model, java.util.concurrent.ForkJoinPool.commonPool()));
    }

    public int retries() { return retries.get(); }
    public int calls() { return calls.get(); }

    public <T> T json(String task, String system, String user, Class<T> type) {
        return json(task, system, user, type, 2);
    }

    public <T> T json(String task, String system, String user, Class<T> type, int maxCalls) {
        return await(jsonAsync(task, system, user, type, maxCalls));
    }

    /**
     * Non-blocking structured call. The repair attempt is part of maxCalls and is only sent after a
     * parse failure; provider/network failures are not silently retried by this layer.
     */
    public <T> CompletableFuture<T> jsonAsync(String task, String system, String user, Class<T> type, int maxCalls) {
        return jsonInternal(task, system, user, type, maxCalls, null);
    }

    /** Same operation as jsonAsync, but reports this invocation's own provider-call count for budget settlement. */
    public <T> CompletableFuture<LlmCallResult<T>> jsonTrackedAsync(
            String task, String system, String user, Class<T> type, int maxCalls) {
        AtomicInteger invocationCalls = new AtomicInteger();
        return jsonInternal(task, system, user, type, maxCalls, invocationCalls)
                .handle((value, error) -> new LlmCallResult<>(value, invocationCalls.get(),
                        error == null ? null : asLlmFailure(error, task)));
    }

    private <T> CompletableFuture<T> jsonInternal(String task, String system, String user,
                                                    Class<T> type, int maxCalls, AtomicInteger invocationCalls) {
        if (maxCalls < 1) {
            return CompletableFuture.failedFuture(new LlmFailure(LlmFailure.Kind.PROVIDER, task, null));
        }
        return callAsync(task, system, user, invocationCalls).thenCompose(raw -> {
            try {
                return CompletableFuture.completedFuture(parse(raw, type));
            } catch (RuntimeException first) {
                if (maxCalls < 2) {
                    return CompletableFuture.failedFuture(new LlmFailure(LlmFailure.Kind.MALFORMED, task, first));
                }
                retries.incrementAndGet();
                String repairPrompt = user + "\n\nYour previous reply was not valid JSON. Reply with ONLY the JSON object.";
                return callAsync(task, system, repairPrompt, invocationCalls).thenApply(repaired -> {
                    try {
                        return parse(repaired, type);
                    } catch (RuntimeException second) {
                        throw new LlmFailure(LlmFailure.Kind.MALFORMED, task, second);
                    }
                });
            }
        }).exceptionallyCompose(error -> CompletableFuture.failedFuture(asLlmFailure(error, task)));
    }

    public String text(String task, String system, String user) {
        return await(textAsync(task, system, user));
    }

    public CompletableFuture<String> textAsync(String task, String system, String user) {
        return textInternal(task, system, user, null);
    }

    /** Same operation as textAsync, with invocation-local usage accounting. */
    public CompletableFuture<LlmCallResult<String>> textTrackedAsync(String task, String system, String user) {
        AtomicInteger invocationCalls = new AtomicInteger();
        return textInternal(task, system, user, invocationCalls)
                .handle((value, error) -> new LlmCallResult<>(value, invocationCalls.get(),
                        error == null ? null : asLlmFailure(error, task)));
    }

    private CompletableFuture<String> textInternal(String task, String system, String user, AtomicInteger invocationCalls) {
        return callAsync(task, system, user, invocationCalls).thenApply(raw -> {
            if (raw.isBlank()) throw new LlmFailure(LlmFailure.Kind.EMPTY, task, null);
            return raw.trim();
        }).exceptionallyCompose(error -> CompletableFuture.failedFuture(asLlmFailure(error, task)));
    }

    private CompletableFuture<String> callAsync(String task, String system, String user, AtomicInteger invocationCalls) {
        calls.incrementAndGet();
        if (invocationCalls != null) invocationCalls.incrementAndGet();
        try {
            CompletableFuture<String> future = gateway.complete(task, system, user);
            if (future == null) return CompletableFuture.failedFuture(
                    new LlmFailure(LlmFailure.Kind.PROVIDER, task, new NullPointerException("gateway returned null future")));
            return future.thenApply(value -> Objects.requireNonNullElse(value, ""));
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(asLlmFailure(error, task));
        }
    }

    private static RuntimeException asLlmFailure(Throwable error, String task) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof LlmFailure failure) return failure;
        if (cause instanceof com.example.retail.llm.SimulationException simulation) return simulation;
        return new LlmFailure(LlmFailure.Kind.PROVIDER, task, cause);
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            throw asLlmFailure(e, "MODEL");
        }
    }

    private static <T> T parse(String raw, Class<T> type) {
        int a = raw.indexOf('{');
        int b = raw.lastIndexOf('}');
        if (a < 0 || b <= a) throw new IllegalArgumentException("no JSON object in reply");
        T value = MAPPER.readValue(raw.substring(a, b + 1), type);
        if (value == null) throw new IllegalArgumentException("empty JSON");
        return value;
    }
}
