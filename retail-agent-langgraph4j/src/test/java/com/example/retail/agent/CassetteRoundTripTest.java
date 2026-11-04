package com.example.retail.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.retail.llm.CassetteMissException;
import com.example.retail.llm.CassetteStore;
import com.example.retail.llm.RecordingChatModel;
import com.example.retail.llm.ReplayChatModel;
import com.example.retail.llm.RetailScriptedResponder;
import com.example.retail.llm.ScriptedChatModel;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CassetteRoundTripTest {

    @TempDir
    Path dir;

    @Test
    void recordThenReplay_reproducesTheRunWithoutTouchingTheDelegate() {
        CassetteStore store = new CassetteStore(dir);
        var delegate = new ScriptedChatModel(RetailScriptedResponder.rules());

        AgentResult recorded = TestAgents.agent(new RecordingChatModel(delegate, store))
                .ask(TestAgents.CUSTOMER, TestAgents.QUERY);
        int callsWhileRecording = delegate.seenPrompts().size();
        assertThat(callsWhileRecording).isPositive();

        AgentResult replayed = TestAgents.agent(new ReplayChatModel(store))
                .ask(TestAgents.CUSTOMER, TestAgents.QUERY);

        assertThat(replayed.answer()).isEqualTo(recorded.answer());
        assertThat(replayed.trace()).isEqualTo(recorded.trace());
        assertThat(replayed.degraded()).isFalse();
        assertThat(delegate.seenPrompts()).hasSize(callsWhileRecording);
    }

    @Test
    void missingCassette_failsLoudlyInsteadOfDegrading() {
        RetailAgent agent = TestAgents.agent(new ReplayChatModel(new CassetteStore(dir)));

        assertThatThrownBy(() -> agent.ask(TestAgents.CUSTOMER, "something never recorded"))
                .satisfies(e -> assertThat(TestAgents.root(e)).isInstanceOf(CassetteMissException.class));
    }
}
