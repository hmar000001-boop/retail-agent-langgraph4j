package com.example.retail.agent;

import java.io.Serial;
import java.io.Serializable;

/**
 * A model-proposed action. Confidence is the model's untrusted self-estimate;
 * score is assigned independently by the runtime evaluator.
 */
public record Proposal(String tool, String args, String rationale, double confidence, double score)
        implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    public Proposal withScore(double value) {
        return new Proposal(tool, args, rationale, confidence, value);
    }
}
