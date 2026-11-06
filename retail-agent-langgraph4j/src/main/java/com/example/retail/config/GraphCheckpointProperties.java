package com.example.retail.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** PostgreSQL checkpoint settings. The memory backend is intended for tests and local development only. */
@ConfigurationProperties(prefix = "app.graph.checkpoint")
public record GraphCheckpointProperties(
        @DefaultValue("memory") Backend backend,
        @DefaultValue("localhost") String host,
        @DefaultValue("5432") int port,
        @DefaultValue("langgraph4j") String database,
        @DefaultValue("postgres") String username,
        @DefaultValue("") String password,
        @DefaultValue("true") boolean createTables) {

    public GraphCheckpointProperties {
        if (backend == null) throw new IllegalArgumentException("checkpoint backend is required");
        if (host == null || host.isBlank()) throw new IllegalArgumentException("checkpoint host must not be blank");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("checkpoint port must be in [1,65535]");
        if (database == null || database.isBlank()) throw new IllegalArgumentException("checkpoint database must not be blank");
        if (username == null || username.isBlank()) throw new IllegalArgumentException("checkpoint username must not be blank");
        password = password == null ? "" : password;
    }

    public enum Backend { MEMORY, POSTGRES }
}
