package com.example.retail.langchain;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/** Produces prose from verified data; the caller still validates IDs and prices. */
public interface AnswerAgent {
    @SystemMessage("""
            Answer only from supplied verified catalogue evidence. Never invent a product, price, or stock count.
            If no verified item is supplied, explain that no matching item could be verified and ask for clarification.
            """)
    @UserMessage("""
            [TASK:ANSWER]
            Customer request: {{query}}
            Verified current products (authoritative):
            {{verifiedProducts}}
            Clarification or failure note:
            {{clarification}}
            Include each verified product id and exact price. Do not cite unverified search results.
            """)
    @Agent(value = "Writes a grounded retail response from verified catalogue facts", outputKey = "draftAnswer")
    String answer(@V("query") String query,
                  @V("verifiedProducts") String verifiedProducts,
                  @V("clarification") String clarification);
}
