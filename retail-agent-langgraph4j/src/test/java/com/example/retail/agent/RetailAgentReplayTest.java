package com.example.retail.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Replays cassettes captured from the real model. Skipped until some exist; once they do, a prompt change that
 * invalidates them fails here (and tells you how to re-record).
 */
@SpringBootTest(properties = {"app.llm.provider=simulated", "app.llm.simulation=replay"})
class RetailAgentReplayTest {

    @BeforeAll
    static void needsCassettes() throws IOException {
        Path dir = Path.of("src/test/resources/cassettes");
        boolean any = false;
        if (Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.list(dir)) {
                any = s.anyMatch(p -> p.toString().endsWith(".cassette"));
            }
        }
        Assumptions.assumeTrue(any, "no cassettes yet: APP_LLM_PROVIDER=real APP_LLM_RECORD=true ./mvnw test");
    }

    @Autowired RetailAgent agent;

    @Test
    void replaysTheRecordedConversation() {
        AgentResult r = agent.ask(TestAgents.CUSTOMER, TestAgents.QUERY);
        assertThat(r.answer()).isNotBlank();
        assertThat(r.degraded()).isFalse();
    }
}
