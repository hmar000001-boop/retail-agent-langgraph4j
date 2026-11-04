package com.example.retail.llm;

/**
 * A misconfigured simulation (no script, no cassette). The agent must NOT degrade gracefully on these:
 * they have to fail the test loudly instead of being masked by the fallback paths.
 */
public class SimulationException extends RuntimeException {
    public SimulationException(String message) {
        super(message);
    }
}
