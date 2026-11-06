package com.example.retail.core;

import java.io.Serializable;

/** Explicit per-run budget. Costs are counts here; a production system should add provider/token cost accounting. */
public record ResourceBudget(int planningRoundsLimit, int toolCallsLimit, int modelCallsLimit,
                             int planningRoundsUsed, int toolCallsUsed, int modelCallsUsed) implements Serializable {
    public static ResourceBudget start(int planningRounds, int toolCalls, int modelCalls) {
        return new ResourceBudget(planningRounds, toolCalls, modelCalls, 0, 0, 0);
    }

    public boolean canPlan() { return planningRoundsUsed < planningRoundsLimit; }
    public boolean canUseTool() { return toolCallsUsed < toolCallsLimit; }
    public boolean canCallModel() { return modelCallsUsed < modelCallsLimit; }
    public int modelCallsRemaining() { return Math.max(0, modelCallsLimit - modelCallsUsed); }
    public ResourceBudget planned() { return new ResourceBudget(planningRoundsLimit, toolCallsLimit, modelCallsLimit, planningRoundsUsed + 1, toolCallsUsed, modelCallsUsed); }
    public ResourceBudget toolUsed() { return new ResourceBudget(planningRoundsLimit, toolCallsLimit, modelCallsLimit, planningRoundsUsed, toolCallsUsed + 1, modelCallsUsed); }
    public ResourceBudget modelUsed() { return modelUsed(1); }
    public ResourceBudget modelUsed(int actualCalls) {
        if (actualCalls < 0) throw new IllegalArgumentException("actualCalls must not be negative");
        if (actualCalls > modelCallsRemaining()) throw new IllegalStateException("model-call budget exceeded");
        return new ResourceBudget(planningRoundsLimit, toolCallsLimit, modelCallsLimit,
                planningRoundsUsed, toolCallsUsed, modelCallsUsed + actualCalls);
    }
}
