package com.example.retail.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.retail.config.LlmProperties;
import com.example.retail.config.LlmProperties.Embeddings;
import com.example.retail.config.LlmProperties.Provider;
import com.example.retail.config.LlmProperties.Simulation;
import com.example.retail.config.LlmStack;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

/** The flag semantics, in one place. */
class LlmStackTest {
    private final ChatModel realStandIn = new ScriptedChatModel(List.of());

    private static LlmProperties props(Provider p, Simulation s, boolean record, List<FaultRule> faults) {
        return new LlmProperties(p, Embeddings.HASH, s, "target/cassettes-test", record, faults,
                "gpt-4o-mini", "", java.time.Duration.ofSeconds(60), 2);
    }

    @Test
    void simulatedDefaultsToScripted() {
        assertThat(LlmStack.build(props(Provider.SIMULATED, Simulation.SCRIPTED, false, List.of()), () -> {
            throw new AssertionError("real provider must not be touched in simulated mode");
        })).isInstanceOf(ScriptedChatModel.class);
    }

    @Test
    void simulatedReplayUsesCassettes() {
        assertThat(LlmStack.build(props(Provider.SIMULATED, Simulation.REPLAY, false, List.of()), () -> realStandIn))
                .isInstanceOf(ReplayChatModel.class);
    }

    @Test
    void realProviderCannotFallBackToTheSpringAiChatModel() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                LlmStack.build(props(Provider.REAL, Simulation.SCRIPTED, false, List.of()), () -> realStandIn))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LangChain4j gateway");
    }

    @Test
    void faultsWrapWhateverIsSelected() {
        var faults = List.of(new FaultRule("*", FaultKind.TIMEOUT, 0, 1));
        assertThat(LlmStack.build(props(Provider.SIMULATED, Simulation.SCRIPTED, false, faults), () -> realStandIn))
                .isInstanceOf(FaultInjectingChatModel.class);
    }
}
