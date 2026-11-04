package com.example.retail.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.retail.llm.FaultInjectingChatModel;
import com.example.retail.llm.FaultKind;
import com.example.retail.llm.FaultRule;
import com.example.retail.llm.RetailScriptedResponder;
import com.example.retail.llm.ScriptedChatModel;
import java.util.List;
import org.junit.jupiter.api.Test;

class FaultInjectionAgentTest {

    private static RetailAgent agentWith(FaultRule... faults) {
        var scripted = new ScriptedChatModel(RetailScriptedResponder.rules());
        return TestAgents.agent(new FaultInjectingChatModel(scripted, List.of(faults)));
    }

    @Test
    void malformedJsonOnce_isRepairedByTheRetry() {
        RetailAgent agent = agentWith(new FaultRule("PROPOSE", FaultKind.MALFORMED_JSON, 0, 1));
        AgentResult r = agent.ask(TestAgents.CUSTOMER, TestAgents.QUERY);

        assertThat(agent.llmRetries()).isEqualTo(1);
        assertThat(r.degraded()).isFalse();
        assertThat(r.answer()).contains("in stock");
    }

    @Test
    void proposeTimeouts_degradeToDeterministicPlanning() {
        AgentResult r = agentWith(new FaultRule("PROPOSE", FaultKind.TIMEOUT, 0, 100))
                .ask(TestAgents.CUSTOMER, TestAgents.QUERY);

        assertThat(r.trace()).contains("propose:fallback");
        assertThat(r.degraded()).isTrue();
        assertThat(r.answer()).contains("in stock");
    }

    @Test
    void lowConfidenceOnFirstTurn_makesTheAgentAskInsteadOfGuess() {
        AgentResult r = agentWith(new FaultRule("EVALUATE", FaultKind.LOW_CONFIDENCE, 0, 100))
                .ask(TestAgents.CUSTOMER, TestAgents.QUERY);

        assertThat(r.clarification()).isTrue();
        assertThat(r.trace()).contains("select:clarify");
        assertThat(r.answer()).contains("?");
    }

    @Test
    void totalProviderOutage_stillReturnsAnAnswerWithinBudget() {
        AgentResult r = agentWith(new FaultRule("*", FaultKind.TIMEOUT, 0, 10_000))
                .ask(TestAgents.CUSTOMER, TestAgents.QUERY);

        assertThat(r.trace()).contains("select:abort", "answer:fallback");
        assertThat(r.degraded()).isTrue();
        assertThat(r.answer()).contains("p-1");
    }
}
