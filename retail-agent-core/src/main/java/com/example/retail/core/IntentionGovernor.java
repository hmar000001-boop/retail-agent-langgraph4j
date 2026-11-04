package com.example.retail.core;

/** Goal authority and method authority are kept separate. Only this component changes the active method. */
public final class IntentionGovernor {
    public enum Decision { CONTINUE, CHANGE_METHOD, SUSPEND, ABANDON, COMPLETE }

    public record Result(IntentionRecord intention, Decision decision, String reason) {}

    public Result afterEvidence(IntentionRecord intention, boolean goalVerified, boolean methodFailed,
                                boolean budgetAvailable) {
        if (goalVerified) return new Result(intention.terminate("COMPLETED"), Decision.COMPLETE, "goal verified by external evidence");
        if (methodFailed && budgetAvailable) {
            return new Result(intention.changeMethod("alternate-search-and-verification"), Decision.CHANGE_METHOD,
                    "method failure; preserve committed goal and change method");
        }
        if (!budgetAvailable) return new Result(intention.terminate("SUSPENDED_BUDGET"), Decision.SUSPEND, "resource budget exhausted");
        return new Result(intention, Decision.CONTINUE, "continue current intention");
    }
}
