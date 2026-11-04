package com.example.retail.config;

import org.springframework.ai.chat.model.ChatModel;

/**
 * The ChatModel the agent uses (real, scripted, replay, recording and/or fault-injecting).
 * A wrapper type on purpose: registering a second ChatModel bean next to Spring AI's own would be ambiguous.
 */
public record AgentLlm(ChatModel model) {}
