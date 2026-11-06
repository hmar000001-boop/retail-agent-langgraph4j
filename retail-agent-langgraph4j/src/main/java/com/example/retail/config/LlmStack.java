package com.example.retail.config;

import com.example.retail.config.LlmProperties.Provider;
import com.example.retail.config.LlmProperties.Simulation;
import com.example.retail.llm.CassetteStore;
import com.example.retail.llm.FaultInjectingChatModel;
import com.example.retail.llm.ReplayChatModel;
import com.example.retail.llm.RetailScriptedResponder;
import com.example.retail.llm.ScriptedChatModel;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.springframework.ai.chat.model.ChatModel;

/**
 * Builds only deterministic simulation/replay ChatModels. Production chat inference is
 * constructed with LangChain4j in AgentConfiguration and never falls back to Spring AI.
 */

public final class LlmStack {
    private LlmStack() {}

    public static ChatModel build(LlmProperties p, Supplier<ChatModel> unusedRealModel) {
        CassetteStore store = new CassetteStore(Path.of(p.cassetteDir()));
        ChatModel base;
        if (p.provider() == Provider.SIMULATED) {
            base = p.simulation() == Simulation.REPLAY
                    ? new ReplayChatModel(store)
                    : new ScriptedChatModel(RetailScriptedResponder.rules());
        } else {
            throw new IllegalStateException("Real chat inference must be built through the LangChain4j gateway, not Spring AI");
        }
        return p.faults().isEmpty() ? base : new FaultInjectingChatModel(base, p.faults());
    }
}
