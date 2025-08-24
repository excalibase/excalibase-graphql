package io.github.excalibase.postgres.service;

import org.postgresql.PGConnection;
import org.postgresql.replication.LogSequenceNumber;
import org.postgresql.replication.PGReplicationStream;
import org.postgresql.replication.fluent.logical.ChainedLogicalStreamBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * PostgreSQL CDC Listener using logical replication
 * Requires PostgreSQL 9.4+ with wal_level = logical
 */
public class PostgresCDCListener {

    private static final Logger logger = LoggerFactory.getLogger(PostgresCDCListener.class);

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String slotName;
    private final String publicationName;
    private final Consumer<CDCEvent> eventHandler;
    private final boolean createSlotIfNotExists;
    private final boolean createPublicationIfNotExists;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Connection connection;
    private PGReplicationStream stream;

    // Add these fields to the class
    private final Map<Integer, RelationInfo> relationMap = new ConcurrentHashMap<>();

    public PostgresCDCListener(String jdbcUrl, String username, String password,
                               String slotName, String publicationName,
                               Consumer<CDCEvent> eventHandler, 
                               boolean createSlotIfNotExists, boolean createPublicationIfNotExists) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.slotName = slotName;
        this.publicationName = publicationName;
        this.eventHandler = eventHandler;
        this.createSlotIfNotExists = createSlotIfNotExists;
        this.createPublicationIfNotExists = createPublicationIfNotExists;
    }

    /**
     * Start the CDC listener
     */
    public void start() throws SQLException {
        if (running.get()) {
            throw new IllegalStateException("CDC Listener is already running");
        }

        logger.info("Starting PostgreSQL CDC Listener...");
        Properties props = new Properties();
        props.setProperty("user", username);
        props.setProperty("password", password);
        props.setProperty("assumeMinServerVersion", "9.4");
        props.setProperty("replication", "database");
        props.setProperty("preferQueryMode", "simple");

        connection = DriverManager.getConnection(jdbcUrl, props);
        
        if (createPublicationIfNotExists) {
            createPublicationIfNotExists();
        }
        
        if (createSlotIfNotExists) {
            createReplicationSlotIfNotExists();
        }

        // Start replication stream
        PGConnection pgConnection = connection.unwrap(PGConnection.class);

        ChainedLogicalStreamBuilder streamBuilder = pgConnection
                .getReplicationAPI()
                .replicationStream()
                .logical()
                .withSlotName(slotName)
                .withSlotOption("proto_version", 1)
                .withSlotOption("publication_names", publicationName);

        stream = streamBuilder.start();
        running.set(true);

        logger.info("CDC Listener started successfully");

        // Start listening in a separate thread
        Thread listenerThread = new Thread(this::listen);
        listenerThread.setDaemon(false);
        listenerThread.setName("postgres-cdc-listener");
        listenerThread.start();
    }

    /**
     * Stop the CDC listener
     */
    public void stop() {
        if (!running.get()) {
            return;
        }

        logger.info("Stopping PostgreSQL CDC Listener...");
        running.set(false);

        try {
            if (stream != null) {
                stream.close();
            }
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (Exception e) {
            logger.error("Error stopping CDC listener", e);
        }

        logger.info("CDC Listener stopped");
    }

    private void listen() {
        logger.info("Starting to listen for CDC events...");

        while (running.get()) {
            try {
                // Read next WAL message (non-blocking with timeout)
                ByteBuffer msg = stream.readPending();

                if (msg == null) {
                    Thread.sleep(10L);
                    continue;
                }

                // Process the message
                processWALMessage(msg, stream.getLastReceiveLSN());

                // Send feedback to PostgreSQL
                stream.setFlushedLSN(stream.getLastReceiveLSN());
                stream.forceUpdateStatus();

            } catch (SQLException e) {
                logger.error("Error reading WAL message", e);
                if (!running.get()) break;

                // Reconnect logic
                try {
                    Thread.sleep(1000);
                    reconnect();
                } catch (Exception reconnectEx) {
                    logger.error("Failed to reconnect", reconnectEx);
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Unexpected error in CDC listener", e);
            }
        }

        logger.info("CDC listener thread terminated");
    }

    private void processWALMessage(ByteBuffer buffer, LogSequenceNumber lsn) {
        try {
            // Reset buffer position for reading
            buffer.rewind();

            // Parse pgoutput binary format
            CDCEvent event = parsePgOutputMessage(buffer, lsn);
            if (event != null) {
                eventHandler.accept(event);
            }

        } catch (Exception e) {
            logger.error("Error processing WAL message", e);
        }
    }

    private CDCEvent parsePgOutputMessage(ByteBuffer buffer, LogSequenceNumber lsn) {
        if (!buffer.hasRemaining()) {
            return null;
        }

        // Read message type (first byte)
        char msgType = (char) buffer.get();

        switch (msgType) {
            case 'B': // BEGIN
                return parseBegin(buffer, lsn);
            case 'C': // COMMIT
                return parseCommit(buffer, lsn);
            case 'R': // RELATION (table schema info)
                return parseRelation(buffer, lsn);
            case 'I': // INSERT
                return parseInsert(buffer, lsn);
            case 'U': // UPDATE
                return parseUpdate(buffer, lsn);
            case 'D': // DELETE
                return parseDelete(buffer, lsn);
            default:
                logger.debug("Unknown message type: {}", msgType);
                return null;
        }
    }

    private CDCEvent parseBegin(ByteBuffer buffer, LogSequenceNumber lsn) {
        // Skip transaction details for now
        return new CDCEvent(CDCEvent.Type.BEGIN, null, null, null, "BEGIN", lsn);
    }

    private CDCEvent parseCommit(ByteBuffer buffer, LogSequenceNumber lsn) {
        return new CDCEvent(CDCEvent.Type.COMMIT, null, null, null, "COMMIT", lsn);
    }

    private CDCEvent parseRelation(ByteBuffer buffer, LogSequenceNumber lsn) {
        // Parse table schema info - store for later use
        int relationId = buffer.getInt();
        String namespace = readString(buffer);
        String relationName = readString(buffer);

        // Store relation info for later message parsing
        relationMap.put(relationId, new RelationInfo(namespace, relationName));

        return null; // Don't emit event for schema info
    }

    private CDCEvent parseInsert(ByteBuffer buffer, LogSequenceNumber lsn) {
        int relationId = buffer.getInt();
        RelationInfo relation = relationMap.get(relationId);

        if (relation == null) {
            logger.warn("Unknown relation ID: {}", relationId);
            return null;
        }

        // Skip tuple type
        buffer.get();

        // Parse new tuple data
        String data = parseTupleData(buffer);

        return new CDCEvent(CDCEvent.Type.INSERT, relation.namespace,
                relation.name, data, "INSERT", lsn);
    }

    private CDCEvent parseUpdate(ByteBuffer buffer, LogSequenceNumber lsn) {
        int relationId = buffer.getInt();
        RelationInfo relation = relationMap.get(relationId);

        if (relation == null) {
            logger.warn("Unknown relation ID: {}", relationId);
            return null;
        }

        String data = "{";

        // Check for old tuple (key/old)
        if (buffer.hasRemaining()) {
            byte tupleType = buffer.get();
            if (tupleType == 'K' || tupleType == 'O') { // Key or Old tuple
                String oldData = parseTupleData(buffer);
                data += "\"old\":" + oldData;
            } else {
                buffer.position(buffer.position() - 1); // Step back
            }
        }

        // Parse new tuple
        if (buffer.hasRemaining()) {
            byte tupleType = buffer.get();
            if (tupleType == 'N') { // New tuple
                String newData = parseTupleData(buffer);
                if (data.length() > 1) data += ", ";
                data += "\"new\":" + newData;
            }
        }

        data += "}";

        return new CDCEvent(CDCEvent.Type.UPDATE, relation.namespace,
                relation.name, data, "UPDATE", lsn);
    }

    private CDCEvent parseDelete(ByteBuffer buffer, LogSequenceNumber lsn) {
        int relationId = buffer.getInt();
        RelationInfo relation = relationMap.get(relationId);

        if (relation == null) {
            logger.warn("Unknown relation ID: {}", relationId);
            return null;
        }

        // Skip tuple type and parse the deleted row data
        if (buffer.hasRemaining()) {
            buffer.get(); // Skip tuple type (K for key, O for old)
            String data = parseTupleData(buffer);
            return new CDCEvent(CDCEvent.Type.DELETE, relation.namespace,
                    relation.name, data, "DELETE", lsn);
        }

        return new CDCEvent(CDCEvent.Type.DELETE, relation.namespace,
                relation.name, "{}", "DELETE", lsn);
    }

    private String readString(ByteBuffer buffer) {
        StringBuilder sb = new StringBuilder();
        byte b;
        while (buffer.hasRemaining() && (b = buffer.get()) != 0) {
            sb.append((char) b);
        }
        return sb.toString();
    }

    private String parseTupleData(ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            return "{}";
        }

        StringBuilder data = new StringBuilder("{");

        try {
            // Read number of columns
            short numColumns = buffer.getShort();

            for (int i = 0; i < numColumns; i++) {
                if (i > 0) data.append(", ");

                // Read column type
                byte colType = buffer.get();

                // Add column key (we'll use column index as key for now)
                data
                        .append("\"col_")
                        .append(i)
                        .append("\":");

                switch (colType) {
                    case 'n': // null
                        data.append("null");
                        break;
                    case 't': // text
                        int length = buffer.getInt();
                        byte[] bytes = new byte[length];
                        buffer.get(bytes);
                        String value = new String(bytes, StandardCharsets.UTF_8);
                        // Escape JSON special characters
                        String escapedValue = value.replace("\\", "\\\\")
                                .replace("\"", "\\\"")
                                .replace("\n", "\\n")
                                .replace("\r", "\\r")
                                .replace("\t", "\\t");
                        data.append("\"").append(escapedValue).append("\"");
                        break;
                    case 'u': // unchanged (for UPDATE)
                        data.append("\"unchanged\"");
                        break;
                    default:
                        // Skip unknown types
                        data.append("\"unknown\"");
                        break;
                }
            }
        } catch (Exception e) {
            logger.debug("Error parsing tuple data: {}", e.getMessage());
            return "{\"parse_error\":\"" + e.getMessage() + "\"}";
        }

        data.append("}");
        return data.toString();
    }

    private void createPublicationIfNotExists() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // Check if publication exists
            String checkSql = "SELECT 1 FROM pg_publication WHERE pubname = '" + publicationName + "'";
            var rs = stmt.executeQuery(checkSql);

            if (!rs.next()) {
                // Create publication for all tables
                String createSql = "CREATE PUBLICATION " + publicationName + " FOR ALL TABLES";
                stmt.execute(createSql);
                logger.info("Created publication: {}", publicationName);
            }
        }
    }

    private void createReplicationSlotIfNotExists() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // Check if slot exists
            String checkSql = "SELECT 1 FROM pg_replication_slots WHERE slot_name = '" + slotName + "'";
            var rs = stmt.executeQuery(checkSql);

            if (!rs.next()) {
                // Create logical replication slot
                String createSql = "SELECT pg_create_logical_replication_slot('" + slotName + "', 'pgoutput')";
                stmt.execute(createSql);
                logger.info("Created replication slot: {}", slotName);
            }
        }
    }

    private void reconnect() throws SQLException, InterruptedException {
        logger.info("Attempting to reconnect...");
        stop();
        Thread.sleep(2000);
        start();
    }

    private static class RelationInfo {
        final String namespace;
        final String name;

        RelationInfo(String namespace, String name) {
            this.namespace = namespace;
            this.name = name;
        }
    }

    // Builder pattern for easier configuration
    public static class Builder {
        private String jdbcUrl;
        private String username;
        private String password;
        private String slotName = "cdc_slot";
        private String publicationName = "cdc_publication";
        private boolean createSlotIfNotExists = true;
        private boolean createPublicationIfNotExists = true;
        private Consumer<CDCEvent> eventHandler;

        public Builder jdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
            return this;
        }

        public Builder credentials(String username, String password) {
            this.username = username;
            this.password = password;
            return this;
        }

        public Builder slotName(String slotName) {
            this.slotName = slotName;
            return this;
        }

        public Builder publicationName(String publicationName) {
            this.publicationName = publicationName;
            return this;
        }

        public Builder eventHandler(Consumer<CDCEvent> eventHandler) {
            this.eventHandler = eventHandler;
            return this;
        }
        
        public Builder createSlotIfNotExists(boolean createSlotIfNotExists) {
            this.createSlotIfNotExists = createSlotIfNotExists;
            return this;
        }
        
        public Builder createPublicationIfNotExists(boolean createPublicationIfNotExists) {
            this.createPublicationIfNotExists = createPublicationIfNotExists;
            return this;
        }

        public PostgresCDCListener build() {
            if (jdbcUrl == null || username == null || password == null || eventHandler == null) {
                throw new IllegalArgumentException("Missing required configuration");
            }
            return new PostgresCDCListener(jdbcUrl, username, password, slotName, publicationName, 
                    eventHandler, createSlotIfNotExists, createPublicationIfNotExists);
        }
    }
}