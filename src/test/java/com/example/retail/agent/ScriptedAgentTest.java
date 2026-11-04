package com.example.retail.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.retail.domain.EpisodicMemory;
import com.example.retail.llm.RetailScriptedResponder;
import com.example.retail.llm.ScriptedChatModel;
import com.example.retail.llm.ScriptedChatModel.Rule;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScriptedAgentTest {

    @Test
    void recommendsAVerifiedProduct_andInterleavesRetrievalWithReasoning() {
        var model = new ScriptedChatModel(RetailScriptedResponder.rules());
        AgentResult r = TestAgents.agent(model).ask(TestAgents.CUSTOMER, TestAgents.QUERY);

        assertThat(r.clarification()).isFalse();
        assertThat(r.degraded()).isFalse();
        assertThat(r.answer()).containsPattern("p-\\d{3}").contains("in stock");
        assertThat(r.iterations()).isEqualTo(2);
        // one full propose-evaluate-select-execute-reflect cycle, then retrieval again, then a second cycle
        assertThat(r.trace()).containsSubsequence(
                "retrieve", "propose", "evaluate", "select:execute", "execute:search_catalog", "reflect",
                "retrieve", "propose", "evaluate", "select:execute", "execute:check_stock", "reflect",
                "learn", "answer");
    }

    @Test
    void remembersEpisodesAcrossSessions() {
        var model = new ScriptedChatModel(RetailScriptedResponder.rules());
        var memory = new EpisodicMemory();
        var agent = TestAgents.agent(model, memory);

        agent.ask(TestAgents.CUSTOMER, TestAgents.QUERY);
        assertThat(memory.recent(TestAgents.CUSTOMER, 5)).hasSize(1);

        int before = model.seenPrompts().size();
        agent.ask(TestAgents.CUSTOMER, "any waterproof jacket?");
        List<String> second = model.seenPrompts().subList(before, model.seenPrompts().size());
        assertThat(second).anyMatch(p -> p.contains("[TASK:PROPOSE]") && p.contains("Previously:"));
    }

    @Test
    void closedActionSpace_unsupportedToolsAreDroppedNotExecuted() {
        List<Rule> rules = new ArrayList<>();
        rules.add(new Rule("PROPOSE", t -> true, t ->
                "{\"proposals\":[{\"tool\":\"delete_orders\",\"args\":\"all\",\"rationale\":\"x\",\"confidence\":0.99}]}"));
        rules.addAll(RetailScriptedResponder.rules());

        AgentResult r = TestAgents.agent(new ScriptedChatModel(rules)).ask(TestAgents.CUSTOMER, TestAgents.QUERY);

        assertThat(r.trace()).contains("propose:fallback").noneMatch(t -> t.contains("delete_orders"));
        assertThat(r.answer()).isNotBlank();
    }
}
