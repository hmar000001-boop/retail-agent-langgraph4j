package com.example.retail.langchain;

import com.example.retail.domain.EpisodicMemory;
import com.example.retail.domain.OrderHistory;
import com.example.retail.domain.ProductCatalog;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

/** CLI entry point; simulated is the default and never requires a provider key. */
public final class RetailAgenticApplication {
    private RetailAgenticApplication() {}

    public static void main(String[] args) {
        String provider = System.getenv().getOrDefault("RETAIL_LLM_PROVIDER", "simulated");
        ChatModel model = switch (provider.toLowerCase()) {
            case "simulated" -> new ScriptedLangChainChatModel();
            case "openai" -> openAiModel();
            default -> throw new IllegalArgumentException("RETAIL_LLM_PROVIDER must be simulated or openai");
        };

        var runtime = new LangChainRetailRuntime(model, new ProductCatalog(), new OrderHistory(), new EpisodicMemory());
        String query = args.length == 0
                ? "Find waterproof trail-running shoes under $150 and recommend only items whose current stock is verified."
                : String.join(" ", args);
        AgentResult result = runtime.ask("c-100", query);
        System.out.println("ANSWER: " + result.answer());
        System.out.println("ITERATIONS: " + result.iterations());
        System.out.println("TRACE:");
        result.trace().forEach(event -> System.out.println("  " + event));
        System.out.println("OBSERVATIONS:");
        result.observations().forEach(observation -> System.out.println("  " + observation));
    }

    private static ChatModel openAiModel() {
        String key = System.getenv("OPENAI_API_KEY");
        if (key == null || key.isBlank()) throw new IllegalStateException("OPENAI_API_KEY is required for openai provider");
        String modelName = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini");
        return OpenAiChatModel.builder().apiKey(key).modelName(modelName).build();
    }
}
