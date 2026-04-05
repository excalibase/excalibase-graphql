package io.github.excalibase.cdc;

/**
 * CDC event from excalibase-watcher (via NATS JetStream or injected directly in tests).
 * Simplified record for the lite SQL compiler — no Jackson annotations needed,
 * ObjectMapper handles records natively.
 */
public record CDCEvent(
        String type,       // INSERT, UPDATE, DELETE, DDL, HEARTBEAT
        String schema,
        String table,
        String data,       // JSON string of row data
        long timestamp
) {}
