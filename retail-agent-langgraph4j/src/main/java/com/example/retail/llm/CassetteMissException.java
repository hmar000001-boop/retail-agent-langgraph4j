package com.example.retail.llm;

public class CassetteMissException extends SimulationException {
    public CassetteMissException(String task, String key) {
        super("No cassette for " + task + " prompt " + key
                + ". Prompts changed? Re-record: APP_LLM_PROVIDER=real APP_LLM_RECORD=true ./mvnw test");
    }
}
