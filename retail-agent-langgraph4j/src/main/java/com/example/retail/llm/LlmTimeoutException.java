package com.example.retail.llm;

/** Injected by the fault layer to mimic a provider timeout. Deliberately NOT a SimulationException. */
public class LlmTimeoutException extends RuntimeException {
    public LlmTimeoutException(String message) {
        super(message);
    }
}
