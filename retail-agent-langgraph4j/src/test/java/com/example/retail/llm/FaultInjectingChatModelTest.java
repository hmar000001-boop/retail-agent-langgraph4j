package com.example.retail.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FaultInjectingChatModelTest {
    @Test
    void lowConfidenceRewritesConfidenceAndScores() {
        assertThat(FaultInjectingChatModel.lowConfidence("{\"scores\":[0.90,0.60]}")).isEqualTo("{\"scores\":[0.1,0.1]}");
        assertThat(FaultInjectingChatModel.lowConfidence("{\"tool\":\"x\",\"confidence\":0.95}"))
                .isEqualTo("{\"tool\":\"x\",\"confidence\":0.1}");
    }
}
