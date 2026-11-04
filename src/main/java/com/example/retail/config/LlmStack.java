package com.example.retail.config;

import com.example.retail.config.LlmProperties.Provider;
import com.example.retail.config.LlmProperties.Simulation;
import com.example.retail.llm.CassetteStore;
import com.example.retail.llm.FaultInjectingChatModel;
import com.example.retail.llm.RecordingChatModel;
import com.example.retail.llm.ReplayChatModel;
import com.example.retail.llm.RetailScriptedResponder;
import com.example.retail.llm.ScriptedChatModel;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.springframework.ai.chat.model.ChatModel;

/**
 * Single place that turns the flag into a ChatModel:
 * <pre>
 * simulated + scripted   -> ScriptedChatModel
 * simulated + replay     -> ReplayChatModel (cassettes)
 * real                   -> the Spring AI OpenAI model
 * real + record          -> RecordingChatModel(real)
 * any + faults           -> FaultInjectingChatModel(...) on top
 * </pre>
 */
public final class LlmStack {
    private LlmStack() {}

    public static ChatModel build(LlmProperties p, Supplier<ChatModel> realModel) {
        CassetteStore store = new CassetteStore(Path.of(p.cassetteDir()));
        ChatModel base;
        if (p.provider() == Provider.SIMULATED) {
            base = p.simulation() == Simulation.REPLAY
                    ? new ReplayChatModel(store)
                    : new ScriptedChatModel(RetailScriptedResponder.rules());
        } else {
            ChatModel real = realModel.get();
            base = p.record() ? new RecordingChatModel(real, store) : real;
        }
        return p.faults().isEmpty() ? base : new FaultInjectingChatModel(base, p.faults());
    }
}
