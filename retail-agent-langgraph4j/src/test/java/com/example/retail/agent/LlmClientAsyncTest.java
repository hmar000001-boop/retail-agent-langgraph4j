package com.example.retail.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LlmClientAsyncTest {
    @Test
    void asyncCallReturnsBeforeProviderFutureCompletesAndTracksUsage() {
        CompletableFuture<String> providerResponse = new CompletableFuture<>();
        LlmClient client = new LlmClient((task, system, user) -> providerResponse);

        CompletableFuture<LlmCallResult<LlmDtos.ReflectionDto>> result = client.jsonTrackedAsync(
                "REFLECT", "system", "user", LlmDtos.ReflectionDto.class, 1);

        assertThat(result).isNotCompleted();
        assertThat(client.calls()).isEqualTo(1);

        providerResponse.complete("{\"goalSatisfied\":true,\"summary\":\"evidence checked\"}");
        LlmCallResult<LlmDtos.ReflectionDto> completed = result.join();

        assertThat(completed.succeeded()).isTrue();
        assertThat(completed.callsUsed()).isEqualTo(1);
        assertThat(completed.value().summary()).isEqualTo("evidence checked");
    }

    @Test
    void boundedJsonRepairIsIncludedInInvocationUsage() {
        AtomicInteger responses = new AtomicInteger();
        LlmClient client = new LlmClient((task, system, user) -> CompletableFuture.completedFuture(
                responses.incrementAndGet() == 1 ? "not json" : "{\"goalSatisfied\":false,\"summary\":\"repaired\"}"));

        LlmCallResult<LlmDtos.ReflectionDto> result = client.jsonTrackedAsync(
                "REFLECT", "system", "user", LlmDtos.ReflectionDto.class, 2).join();

        assertThat(result.succeeded()).isTrue();
        assertThat(result.callsUsed()).isEqualTo(2);
        assertThat(client.calls()).isEqualTo(2);
        assertThat(client.retries()).isEqualTo(1);
        assertThat(result.value().summary()).isEqualTo("repaired");
    }

    @Test
    void concurrentInvocationsDoNotChargeEachOthersModelCalls() {
        CompletableFuture<String> firstProvider = new CompletableFuture<>();
        CompletableFuture<String> secondProvider = new CompletableFuture<>();
        AtomicInteger invocation = new AtomicInteger();
        LlmClient client = new LlmClient((task, system, user) ->
                invocation.getAndIncrement() == 0 ? firstProvider : secondProvider);

        var first = client.textTrackedAsync("ANSWER", "system", "first");
        var second = client.textTrackedAsync("ANSWER", "system", "second");
        firstProvider.complete("first response");
        secondProvider.complete("second response");

        assertThat(first.join().callsUsed()).isEqualTo(1);
        assertThat(second.join().callsUsed()).isEqualTo(1);
        assertThat(client.calls()).isEqualTo(2);
    }
}
