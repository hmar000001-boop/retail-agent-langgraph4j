package com.example.retail.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.retail.llm.CassetteStore;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingLlmGatewayTest {
    @TempDir Path tempDir;

    @Test
    void recordsCanonicalTaskPromptAndResponseForOfflineReplay() {
        LlmGateway delegate = (task, system, user) -> CompletableFuture.completedFuture("{\"ok\":true}");
        CassetteStore store = new CassetteStore(tempDir);
        RecordingLlmGateway recorder = new RecordingLlmGateway(delegate, store);

        assertThat(recorder.complete("PROPOSE", "system", "workspace").join()).isEqualTo("{\"ok\":true}");
        assertThat(store.read("system\n[TASK:PROPOSE]\nworkspace")).contains("{\"ok\":true}");
    }
}
