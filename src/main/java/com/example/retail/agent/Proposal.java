package com.example.retail.agent;

/** One candidate next action. confidence = proposer's own estimate, score = evaluator's value. */
public record Proposal(String tool, String args, String rationale, double confidence, double score) {
    public Proposal withScore(double s) {
        return new Proposal(tool, args, rationale, confidence, s);
    }
}
