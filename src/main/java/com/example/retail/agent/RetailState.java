package com.example.retail.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

/**
 * CoALA working memory: everything that persists across LLM calls within one session.
 * Scalars are overwritten; OBSERVATIONS and TRACE are append-only channels.
 */
public class RetailState extends AgentState {
    public static final String QUERY = "query";
    public static final String CUSTOMER = "customer";
    public static final String ITERATION = "iteration";
    public static final String MEMORY = "memory";
    public static final String OBSERVATIONS = "observations";
    public static final String PROPOSALS = "proposals";
    public static final String SELECTED = "selected";
    public static final String ROUTE = "route";
    public static final String FAILURES = "failures";
    public static final String CLARIFICATION = "clarification";
    public static final String SUMMARY = "summary";
    public static final String ANSWER = "answer";
    public static final String TRACE = "trace";

    public static final Map<String, Channel<?>> SCHEMA = Map.<String, Channel<?>>of(
            OBSERVATIONS, Channels.appender(ArrayList::new),
            TRACE, Channels.appender(ArrayList::new));

    public RetailState(Map<String, Object> init) {
        super(init);
    }

    public String query() { return this.<String>value(QUERY).orElse(""); }
    public String customer() { return this.<String>value(CUSTOMER).orElse(""); }
    public int iteration() { return this.<Integer>value(ITERATION).orElse(0); }
    public String memory() { return this.<String>value(MEMORY).orElse(""); }
    public List<String> observations() { return this.<List<String>>value(OBSERVATIONS).orElse(List.of()); }
    public List<Proposal> proposals() { return this.<List<Proposal>>value(PROPOSALS).orElse(List.of()); }
    public Optional<Proposal> selected() { return this.<Proposal>value(SELECTED); }
    public String route() { return this.<String>value(ROUTE).orElse("abort"); }
    public int failures() { return this.<Integer>value(FAILURES).orElse(0); }
    public String clarification() { return this.<String>value(CLARIFICATION).orElse(""); }
    public String summary() { return this.<String>value(SUMMARY).orElse(""); }
    public String answer() { return this.<String>value(ANSWER).orElse(""); }
    public List<String> trace() { return this.<List<String>>value(TRACE).orElse(List.of()); }
}
