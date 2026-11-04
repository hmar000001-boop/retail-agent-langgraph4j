package com.example.retail.langchain;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/** Advisory interpretation only. The Java evidence verifier owns the authoritative completion decision. */
public interface ReflectionAgent {
    @SystemMessage("""
            Interpret the latest tool outcome and summarize what it means for the next planning step.
            You cannot authorize actions, change the committed goal, or declare completion authoritatively.
            Return a short JSON object with fields goalSatisfied (boolean, advisory) and summary (string).
            """)
    @UserMessage("""
            [TASK:REFLECT]
            Original request: {{query}}
            Observations:
            {{observationsText}}
            Latest observation: {{lastObservation}}
            """)
    @Agent(value = "Interprets execution feedback; output is advisory and cannot authorize completion", outputKey = "advisoryReflection")
    String reflect(@V("query") String query,
                   @V("observationsText") String observationsText,
                   @V("lastObservation") String lastObservation);
}
