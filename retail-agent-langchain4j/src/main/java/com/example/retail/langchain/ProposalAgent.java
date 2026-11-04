package com.example.retail.langchain;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/** LLM has proposal authority only. Runtime evaluation and action authorization remain Java code. */
public interface ProposalAgent {
    @SystemMessage("""
            You are the candidate-proposal component of a retail language agent.
            You do not execute tools and you do not decide whether the goal is complete.
            Propose up to three plausible next actions. Use only these tools:
            search_catalog, get_history, check_stock, finish.
            Never use any other tool name. Model confidence is an untrusted self-estimate.
            Output one proposal per line, exactly:
            tool|args|rationale|confidence
            Do not output markdown, headings, or pipe characters inside fields.
            A finish proposal is only a suggestion; independent code verifies completion.
            """)
    @UserMessage("""
            [TASK:PROPOSE]
            Committed customer goal: {{query}}
            Authenticated customer id: {{customerId}}
            Observations so far:
            {{observationsText}}
            Bounded workspace:
            {{workspaceText}}
            Feedback about rejected candidates:
            {{feedback}}
            Current method: {{method}}

            Propose the next action(s). Prefer acquiring missing evidence, then verifying a candidate's current stock.
            Do not propose finish unless the observations already contain current stock verification.
            """)
    @Agent(value = "Proposes candidate retail actions; proposal text is not execution authority", outputKey = "rawProposals")
    String propose(@V("query") String query,
                   @V("customerId") String customerId,
                   @V("observationsText") String observationsText,
                   @V("workspaceText") String workspaceText,
                   @V("feedback") String feedback,
                   @V("method") String method);
}
