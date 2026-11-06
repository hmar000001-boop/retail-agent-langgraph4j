package com.example.retail.agent.checkpoint;

import java.util.Collection;
import java.util.Optional;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.bsc.langgraph4j.checkpoint.MemorySaver;

/**
 * Thread-safe in-process checkpoint saver for local execution and tests.
 * It is intentionally not durable across JVM restarts; production must inject a durable saver.
 */
public final class SynchronizedMemorySaver implements BaseCheckpointSaver {
    private final MemorySaver delegate = new MemorySaver();

    @Override public synchronized Collection<Checkpoint> list(RunnableConfig config) { return delegate.list(config); }
    @Override public synchronized Optional<Checkpoint> get(RunnableConfig config) { return delegate.get(config); }
    @Override public synchronized RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
        return delegate.put(config, checkpoint);
    }
    @Override public synchronized Tag release(RunnableConfig config) throws Exception { return delegate.release(config); }
}
