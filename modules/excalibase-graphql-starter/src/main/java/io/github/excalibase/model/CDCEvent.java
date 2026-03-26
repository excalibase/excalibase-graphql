package io.github.excalibase.model;

/**
 * CDC event received from excalibase-watcher via NATS JetStream.
 * <p>
 * This mirrors the watcher's {@code CDCEvent} JSON structure so Jackson can deserialize
 * NATS message payloads directly into this class.
 * </p>
 */
public class CDCEvent {

    public enum Type {
        BEGIN, COMMIT, INSERT, UPDATE, DELETE, DDL, TRUNCATE, HEARTBEAT
    }

    private Type type;
    private String schema;
    private String table;
    private String data;
    private String rawMessage;
    private String lsn;
    private long timestamp;
    private long sourceTimestamp;

    public CDCEvent() {
    }

    public CDCEvent(Type type, String schema, String table, String data,
                    String rawMessage, String lsn, long timestamp, long sourceTimestamp) {
        this.type = type;
        this.schema = schema;
        this.table = table;
        this.data = data;
        this.rawMessage = rawMessage;
        this.lsn = lsn;
        this.timestamp = timestamp;
        this.sourceTimestamp = sourceTimestamp;
    }

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    public String getSchema() { return schema; }
    public void setSchema(String schema) { this.schema = schema; }

    public String getTable() { return table; }
    public void setTable(String table) { this.table = table; }

    public String getData() { return data; }
    public void setData(String data) { this.data = data; }

    public String getRawMessage() { return rawMessage; }
    public void setRawMessage(String rawMessage) { this.rawMessage = rawMessage; }

    public String getLsn() { return lsn; }
    public void setLsn(String lsn) { this.lsn = lsn; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public long getSourceTimestamp() { return sourceTimestamp; }
    public void setSourceTimestamp(long sourceTimestamp) { this.sourceTimestamp = sourceTimestamp; }

    @Override
    public String toString() {
        return String.format("CDCEvent{type=%s, schema=%s, table=%s, timestamp=%d}",
                type, schema, table, timestamp);
    }
}
