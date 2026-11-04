package com.example.retail.llm;

import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Inject {@code kind} into {@code times} calls of {@code task}, after letting {@code skip} matching calls through.
 * Note: the agent's JSON retry is itself a call, so times=1 on a JSON task is recovered by the retry.
 */
public record FaultRule(@DefaultValue("*") String task, FaultKind kind, int skip, @DefaultValue("1") int times) {}
