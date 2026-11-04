package com.example.retail.agent;

/** Typed LLM failure: the governor reacts to the kind, it never lets it crash the loop. */
public class LlmFailure extends RuntimeException {
    public enum Kind { PROVIDER, MALFORMED, EMPTY }

    private final Kind kind;

    public LlmFailure(Kind kind, String task, Throwable cause) {
        super(kind + " failure in " + task, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
