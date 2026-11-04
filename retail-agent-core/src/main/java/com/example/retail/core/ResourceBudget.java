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
    public ResourceBudget planned() { return new ResourceBudget(planningRoundsLimit, toolCallsLimit, modelCallsLimit, planningRoundsUsed + 1, toolCallsUsed, modelCallsUsed); }
    public ResourceBudget toolUsed() { return new ResourceBudget(planningRoundsLimit, toolCallsLimit, modelCallsLimit, planningRoundsUsed, toolCallsUsed + 1, modelCallsUsed); }
    public ResourceBudget modelUsed() { return new ResourceBudget(planningRoundsLimit, toolCallsLimit, modelCallsLimit, planningRoundsUsed, toolCallsUsed, modelCallsUsed + 1); }
}
