package com.example.retail.llm;

public enum FaultKind {
    /** Provider call throws. */
    TIMEOUT,
    /** Reply is not parseable JSON. */
    MALFORMED_JSON,
    /** Reply is an empty string. */
    EMPTY,
    /** Real reply, but every confidence / score is rewritten to 0.1. */
    LOW_CONFIDENCE
}
