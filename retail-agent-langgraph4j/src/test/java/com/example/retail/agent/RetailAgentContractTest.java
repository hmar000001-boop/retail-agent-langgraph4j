package com.example.retail.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.retail.domain.ProductCatalog;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Provider-agnostic: asserts invariants only, so the SAME test runs against the simulator (default)
 * and against OpenAI (mvn test -Dapp.llm.provider=real). Set APP_LLM_RECORD=true explicitly to capture cassettes.
 */
@SpringBootTest
class RetailAgentContractTest {

    @BeforeAll
    static void realModeNeedsAKey() {
        String provider = System.getProperty("app.llm.provider", System.getenv().getOrDefault("APP_LLM_PROVIDER", "simulated"));
        if ("real".equalsIgnoreCase(provider)) {
            String key = System.getenv("OPENAI_API_KEY");
            Assumptions.assumeTrue(key != null && !key.isBlank(), "OPENAI_API_KEY not set; skipping real-LLM contract test");
        }
    }

    @Autowired RetailAgent agent;
    @Autowired ProductCatalog catalog;

    @Test
    @Timeout(180)
    void answersAreGroundedInTheCatalogAndWithinBudget() {
        AgentResult r = agent.ask(TestAgents.CUSTOMER, TestAgents.QUERY);

        assertThat(r.answer()).isNotBlank();
        assertThat(r.iterations()).isLessThanOrEqualTo(3);
        assertThat(r.trace().get(0)).startsWith("retrieve:cycle=1:plan=0");
        assertThat(r.trace().get(r.trace().size() - 1)).startsWith("answer");
        boolean grounded = catalog.all().stream().anyMatch(p ->
                r.answer().contains(p.id()) || r.answer().toLowerCase().contains(p.name().toLowerCase()));
        assertThat(r.clarification() || grounded)
                .as("answer must ask a question or mention a real catalog product: %s", r.answer())
                .isTrue();
    }
}
