package io.github.excalibase.postgres.service;

import org.postgresql.replication.LogSequenceNumber;

/**
 * CDC Event class representing database changes
 * Captures PostgreSQL logical replication events
 */
public class CDCEvent {
    public enum Type {
        BEGIN, COMMIT, INSERT, UPDATE, DELETE
    }

    private final Type type;
    private final String schema;
    private final String table;
    private final String data;
    private final String rawMessage;
    private final LogSequenceNumber lsn;
    private final long timestamp;

    public CDCEvent(Type type, String schema, String table, String data,
                    String rawMessage, LogSequenceNumber lsn) {
        this.type = type;
        this.schema = schema;
        this.table = table;
        this.data = data;
        this.rawMessage = rawMessage;
        this.lsn = lsn;
        this.timestamp = System.currentTimeMillis();
    }

    // Getters
    public Type getType() { return type; }
    public String getSchema() { return schema; }
    public String getTable() { return table; }
    public String getData() { return data; }
    public String getRawMessage() { return rawMessage; }
    public LogSequenceNumber getLsn() { return lsn; }
    public long getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        return String.format("CDCEvent{type=%s, schema=%s, table=%s, data=%s, lsn=%s, timestamp=%d}",
                type, schema, table, data, lsn, timestamp);
    }
}