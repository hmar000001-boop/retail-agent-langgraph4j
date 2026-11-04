package com.example.retail.agent;

import com.example.retail.llm.SimulationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import tools.jackson.databind.json.JsonMapper;

/** Thin Spring AI ChatClient wrapper: JSON parsing, one repair retry, typed failures. */
public class LlmClient {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final ChatClient chat;
    private final AtomicInteger retries = new AtomicInteger();

    public LlmClient(ChatModel model) {
        this.chat = ChatClient.create(model);
    }

    public int retries() {
        return retries.get();
    }

    public <T> T json(String task, String system, String user, Class<T> type) {
        String raw = complete(task, system, user);
        try {
            return parse(raw, type);
        } catch (RuntimeException first) {
            retries.incrementAndGet();
            String again = complete(task, system,
                    user + "\n\nYour previous reply was not valid JSON. Reply with ONLY the JSON object.");
            try {
                return parse(again, type);
            } catch (RuntimeException second) {
                throw new LlmFailure(LlmFailure.Kind.MALFORMED, task, second);
            }
        }
    }

    public String text(String task, String system, String user) {
        String raw = complete(task, system, user);
        if (raw.isBlank()) throw new LlmFailure(LlmFailure.Kind.EMPTY, task, null);
        return raw.trim();
    }

    private String complete(String task, String system, String user) {
        try {
            String s = chat.prompt().system(system).user(user).call().content();
            return s == null ? "" : s;
        } catch (RuntimeException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof SimulationException se) throw se; // config errors must fail loudly
            }
            throw new LlmFailure(LlmFailure.Kind.PROVIDER, task, e);
        }
    }

    private static <T> T parse(String raw, Class<T> type) {
        int a = raw.indexOf('{');
        int b = raw.lastIndexOf('}');
        if (a < 0 || b <= a) throw new IllegalArgumentException("no JSON object in reply");
        T value = MAPPER.readValue(raw.substring(a, b + 1), type);
        if (value == null) throw new IllegalArgumentException("empty JSON");
        return value;
    }
}
