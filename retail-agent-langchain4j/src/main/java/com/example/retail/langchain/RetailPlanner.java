package com.example.retail.langchain;

import dev.langchain4j.agentic.planner.Action;
import dev.langchain4j.agentic.planner.AgentInstance;
import dev.langchain4j.agentic.planner.InitPlanningContext;
import dev.langchain4j.agentic.planner.Planner;
import dev.langchain4j.agentic.planner.PlanningContext;
import java.util.List;
import java.util.Map;

/**
 * Custom LangChain4j Agentic planner implementing a state-driven control loop.
 * It deliberately mirrors the LangGraph4j control semantics, not its graph representation.
 * Stage is persisted as a primitive so AgenticScope recovery can restore the planner cursor.
 */
public final class RetailPlanner implements Planner {
    private static final String STAGE_KEY = "retailPlannerStage";
    private List<AgentInstance> agents = List.of();
    private int stage;

    @Override
    public void init(InitPlanningContext context) {
        this.agents = context.subagents();
        if (agents.size() != 14) {
            throw new IllegalStateException("RetailPlanner expects 14 subagents, got " + agents.size());
        }
    }

    @Override
    public Action firstAction(PlanningContext context) {
        // A planner instance may serve multiple requests. The scope is authoritative for a resumed
        // request; a new scope has no cursor and must start at stage zero.
        Object savedStage = context.agenticScope().readState(STAGE_KEY);
        stage = savedStage instanceof Number number ? number.intValue() : 0;
        if (stage < 0 || stage > 14) stage = 0;
        persistStage(context);
        return stage == 14
                ? done(context.agenticScope().readState("resultEnvelope", Map.of()))
                : call(agents.get(stage));
    }

    @Override
    public Action nextAction(PlanningContext context) {
        var scope = context.agenticScope();
        return switch (stage) {
            case 0 -> go(1, context); // retrieve -> reserve proposal model budget
            case 1 -> scope.readState("proposalModelAllowed", false)
                    ? go(2, context) : go(3, context); // reserve -> proposal or deterministic fallback -> evaluate
            case 2 -> go(3, context); // proposal -> evaluate
            case 3 -> go(4, context); // evaluate -> select
            case 4 -> switch (scope.readState("decision", "ABORT")) {
                case "EXECUTE" -> go(5, context);
                case "REPLAN" -> go(0, context);
                case "LEARN" -> go(10, context);
                case "ANSWER" -> go(10, context);
                default -> go(10, context);
            };
            case 5 -> go(6, context); // execute -> reserve reflection call
            case 6 -> scope.readState("reflectionModelAllowed", false)
                    ? go(7, context) : go(8, context); // reflection LLM is advisory; Java monitor always runs
            case 7 -> go(8, context); // advisory reflection -> authoritative Java monitor
            case 8 -> switch (scope.readState("reflectRoute", "ANSWER")) {
                case "CONTINUE" -> go(0, context);
                case "RECOVER" -> go(9, context);
                case "LEARN" -> go(10, context);
                default -> go(10, context);
            };
            case 9 -> scope.readState("recoveryResult", "").equals("UNRECOVERABLE")
                    ? go(10, context) : go(0, context); // bounded recovery -> parent loop or safe answer
            case 10 -> go(11, context); // learn -> reserve answer model budget
            case 11 -> scope.readState("answerModelAllowed", false)
                    ? go(12, context) : go(13, context); // skip LLM answer when budget is exhausted
            case 12 -> go(13, context); // answer agent -> finalizer
            case 13 -> {
                stage = 14;
                persistStage(context);
                yield done(scope.readState("resultEnvelope", Map.of("answer", "No verified recommendation is available.")));
            }
            default -> done(scope.readState("resultEnvelope", Map.of("answer", "No verified recommendation is available.")));
        };
    }

    private Action go(int nextStage, PlanningContext context) {
        stage = nextStage;
        persistStage(context);
        return call(agents.get(nextStage));
    }

    private void persistStage(PlanningContext context) {
        context.agenticScope().writeState(STAGE_KEY, stage);
    }

    @Override
    public Map<String, Object> executionState() { return Map.of("stage", stage); }

    @Override
    public void restoreExecutionState(Map<String, Object> state) {
        Object saved = state.get("stage");
        if (saved instanceof Number number) stage = number.intValue();
    }
}
