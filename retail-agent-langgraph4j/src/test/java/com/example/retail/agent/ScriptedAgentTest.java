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
                "retrieve:cycle=1:plan=0", "propose:cycle=1:plan=0", "evaluate:cycle=1:plan=0",
                "select:execute:cycle=1:plan=0", "execute:search_catalog:cycle=1",
                "reflect:evidence-verified=false:cycle=1",
                "retrieve:cycle=2:plan=0", "propose:cycle=2:plan=0", "evaluate:cycle=2:plan=0",
                "select:execute:cycle=2:plan=0", "execute:check_stock:cycle=2",
                "reflect:evidence-verified=true:cycle=2", "learn", "answer");
    }


    @Test
    void waterproofRequestDoesNotTreatTheCheaperNonWaterproofShoeAsAValidMatch() {
        RetailAgent agent = TestAgents.agent(new ScriptedChatModel(RetailScriptedResponder.rules()));
        AgentResult result = agent.ask(TestAgents.CUSTOMER,
                "Find waterproof trail-running shoes under $150 and verify current stock.");

        assertThat(result.answer()).contains("p-103", "$149.0", "in stock");
        assertThat(result.observations()).contains("[check_stock] p-103 stock=4 price=149.0");
        assertThat(result.trace()).anyMatch(event -> event.startsWith("reflect:evidence-verified=true:"));
    }

    @Test
    void adversarialSimulatorCannotAuthorizePrematureFinishEvenWithHighConfidence() {
        List<Rule> rules = new ArrayList<>();
        rules.add(new Rule("PROPOSE", t -> true, t ->
                "{\"proposals\":[{\"tool\":\"finish\",\"args\":\"\",\"rationale\":\"claim success before evidence\",\"confidence\":0.99}]}"));
        rules.addAll(RetailScriptedResponder.rules());

        AgentResult result = TestAgents.agent(new ScriptedChatModel(rules))
                .ask(TestAgents.CUSTOMER, "Find waterproof trail-running shoes under $150.");

        assertThat(result.answer()).contains("Could you tell me");
        assertThat(result.observations()).isEmpty();
        assertThat(result.trace()).anyMatch(event -> event.startsWith("select:reject-all:replan="));
        assertThat(result.trace()).noneMatch(event -> event.startsWith("execute:"));
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

        assertThat(r.trace()).anyMatch(event -> event.startsWith("propose:fallback:"));
        assertThat(r.trace()).noneMatch(t -> t.contains("delete_orders"));
        assertThat(r.answer()).isNotBlank();
    }
}
