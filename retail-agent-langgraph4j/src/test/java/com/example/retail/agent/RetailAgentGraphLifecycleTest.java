package com.example.retail.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.retail.llm.RetailScriptedResponder;
import com.example.retail.llm.ScriptedChatModel;
import org.junit.jupiter.api.Test;

class RetailAgentGraphLifecycleTest {
    @Test
    void explicitThreadIdCannotBeStartedTwice() {
        RetailAgent agent = TestAgents.agent(new ScriptedChatModel(RetailScriptedResponder.rules()));
        String threadId = "lifecycle-duplicate-thread";

        AgentResult first = agent.start(threadId, TestAgents.CUSTOMER, TestAgents.QUERY);

        assertThat(first.threadId()).isEqualTo(threadId);
        assertThatThrownBy(() -> agent.start(threadId, TestAgents.CUSTOMER, TestAgents.QUERY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("checkpoint already exists");
    }

    @Test
    void checkpointOwnerIsCheckedBeforeApprovalStatus() {
        RetailAgent agent = TestAgents.agent(new ScriptedChatModel(RetailScriptedResponder.rules()));
        String threadId = "lifecycle-owner-thread";
        agent.start(threadId, TestAgents.CUSTOMER, TestAgents.QUERY);

        assertThatThrownBy(() -> agent.resumeApproval(threadId, "different-customer", "irrelevant-id", true))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("does not own");
    }
}
