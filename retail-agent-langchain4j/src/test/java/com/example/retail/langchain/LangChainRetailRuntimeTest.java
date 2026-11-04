package com.example.retail.langchain;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.retail.domain.EpisodicMemory;
import com.example.retail.domain.OrderHistory;
import com.example.retail.domain.ProductCatalog;
import org.junit.jupiter.api.Test;

class LangChainRetailRuntimeTest {
    @Test
    void plannerRunsRetrieveProposeEvaluateSelectExecuteReflectAndAnswer() {
        ScriptedLangChainChatModel model = new ScriptedLangChainChatModel();
        LangChainRetailRuntime runtime = new LangChainRetailRuntime(
                model, new ProductCatalog(), new OrderHistory(), new EpisodicMemory());

        AgentResult result = runtime.ask("c-100",
                "Find waterproof trail-running shoes under $150 and verify current stock.");

        assertThat(result.answer()).contains("p-103", "$149.0", "in stock");
        assertThat(result.observations()).anyMatch(value -> value.equals("[check_stock] p-103 stock=4 price=149.0"));
        assertThat(result.trace()).containsSubsequence("retrieve", "propose", "evaluate", "select:execute",
                "execute:search_catalog", "reflect", "retrieve", "propose", "evaluate", "select:execute",
                "execute:check_stock", "reflect", "learn:outcome=verified", "answer:model-grounded");
        assertThat(result.trace().stream().filter("propose"::equals)).hasSize(2);
        assertThat(result.iterations()).isEqualTo(2);
        assertThat(model.calls()).isEqualTo(5);
    }


    @Test
    void adversarialSimulatorCannotAuthorizePrematureFinishEvenWithHighConfidence() {
        ScriptedLangChainChatModel model = new ScriptedLangChainChatModel(true);
        LangChainRetailRuntime runtime = new LangChainRetailRuntime(
                model, new ProductCatalog(), new OrderHistory(), new EpisodicMemory());

        AgentResult result = runtime.ask("c-100", "Find waterproof trail-running shoes under $150.");

        assertThat(result.answer()).contains("could not verify");
        assertThat(result.observations()).noneMatch(value -> value.startsWith("[check_stock]"));
        assertThat(result.trace()).anyMatch(event -> event.startsWith("select:reject-all:replan="));
        assertThat(result.trace()).noneMatch(event -> event.equals("select:execute") || event.equals("execute:finish"));
    }

    @Test
    void plannerStartsASecondRequestAtTheBeginning() {
        ScriptedLangChainChatModel model = new ScriptedLangChainChatModel();
        LangChainRetailRuntime runtime = new LangChainRetailRuntime(
                model, new ProductCatalog(), new OrderHistory(), new EpisodicMemory());

        AgentResult first = runtime.ask("c-100", "Find waterproof trail-running shoes under $150.");
        AgentResult second = runtime.ask("c-200", "Find waterproof trail-running shoes under $150.");

        assertThat(first.answer()).contains("p-103", "$149.0");
        assertThat(second.answer()).contains("p-103", "$149.0");
        assertThat(second.trace()).startsWith("retrieve");
        assertThat(second.trace()).contains("execute:search_catalog", "execute:check_stock");
    }

    @Test
    void simulatorCannotAuthorizeExecutionOrCompleteTheGoalOnItsOwn() {
        ScriptedLangChainChatModel model = new ScriptedLangChainChatModel();
        LangChainRetailRuntime runtime = new LangChainRetailRuntime(
                model, new ProductCatalog(), new OrderHistory(), new EpisodicMemory());
        AgentResult result = runtime.ask("c-100", "Find waterproof trail-running shoes under $150.");

        assertThat(result.trace()).contains("evaluate:independent-evidence-policy", "reflect:evidence-verified=true");
        assertThat(result.observations()).noneMatch(value -> value.contains("[finish]"));
    }
}
