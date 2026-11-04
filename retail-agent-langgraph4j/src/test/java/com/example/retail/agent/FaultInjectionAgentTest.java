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

        assertThat(r.trace()).anyMatch(event -> event.startsWith("propose:fallback:"));
        assertThat(r.degraded()).isTrue();
        assertThat(r.answer()).contains("in stock");
    }

    @Test
    void lowSelfReportedConfidence_doesNotOverrideIndependentEvidenceEvaluation() {
        AgentResult r = agentWith(new FaultRule("PROPOSE", FaultKind.LOW_CONFIDENCE, 0, 100))
                .ask(TestAgents.CUSTOMER, TestAgents.QUERY);

        assertThat(r.clarification()).isFalse();
        assertThat(r.trace()).anyMatch(event -> event.startsWith("evaluate:independent-evidence-policy:"));
        assertThat(r.answer()).contains("in stock");
    }

    @Test
    void totalProviderOutage_stillReturnsAnAnswerWithinBudget() {
        AgentResult r = agentWith(new FaultRule("*", FaultKind.TIMEOUT, 0, 10_000))
                .ask(TestAgents.CUSTOMER, TestAgents.QUERY);

        assertThat(r.trace()).anyMatch(event -> event.startsWith("reflect:evidence-verified=true:"));
        assertThat(r.trace()).contains("answer:fallback");
        assertThat(r.degraded()).isTrue();
        String stockObservation = r.observations().stream()
                .filter(o -> o.startsWith("[check_stock] ") && o.matches(".*stock=[1-9][0-9]*.*"))
                .findFirst().orElseThrow(() -> new AssertionError("no positive stock verification: " + r.observations()));
        java.util.regex.Matcher id = java.util.regex.Pattern.compile("p-\\d{3}").matcher(stockObservation);
        assertThat(id.find()).isTrue();
        assertThat(r.answer()).contains(id.group()).contains("in stock");
    }
}
