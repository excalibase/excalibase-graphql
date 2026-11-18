package io.github.excalibase.metadata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads the newest document row. A connection is opened per poll, so a restarted database or a pooler that
 * dropped its sessions needs no recovery logic. The credentials go to the driver as properties and nothing
 * the driver says is passed on: its messages can carry the host, the user or the URL, so a failure is reduced
 * to a fixed sentence and the SQLState.
 */
final class DbDocumentLoader implements ReloadingMetadataSource.Loader {

    static final String TABLE = "excalibase_meta.metadata_documents";
    private static final String POSTGRES_URL = "jdbc:postgresql:";
    private static final String CREATE_SCHEMA = "CREATE SCHEMA IF NOT EXISTS excalibase_meta";
    private static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS " + TABLE + " (version bigint PRIMARY KEY, "
            + "document text NOT NULL, created_at timestamptz NOT NULL DEFAULT now())";
    private static final String NEWEST = "SELECT octet_length(document), "
            + "CASE WHEN octet_length(document) > ? THEN NULL ELSE document END FROM " + TABLE
            + " ORDER BY version DESC LIMIT 1";
    private static final int SOCKET_GRACE_SECONDS = 5;

    private final String url;
    private final Properties credentials;
    private final boolean migrate;
    private final long queryTimeoutMillis;
    private final AtomicBoolean migrated = new AtomicBoolean();

    /** Opens the connection a read uses; a test supplies one it can inspect afterwards. */
    @FunctionalInterface
    interface Connector {
        Connection open() throws SQLException;
    }

    private final Connector connector;

    DbDocumentLoader(DbSettings settings) {
        this(settings, null);
    }

    DbDocumentLoader(DbSettings settings, Connector connector) {
        if (settings.url() == null || !settings.url().startsWith(POSTGRES_URL)) {
            throw new IllegalStateException("metadata.source=db supports Postgres only: the metadata database URL "
                    + "must start with " + POSTGRES_URL);
        }
        this.url = settings.url();
        this.migrate = settings.migrate();
        this.queryTimeoutMillis = Math.max(1, settings.queryTimeout().toMillis());
        this.credentials = new Properties();
        put("user", settings.username());
        put("password", settings.password());
        credentials.setProperty("connectTimeout", Long.toString(Math.max(1, settings.connectTimeout().toSeconds())));
        String giveUpAfter = Long.toString(settings.queryTimeout().toSeconds() + SOCKET_GRACE_SECONDS);
        credentials.setProperty("socketTimeout", giveUpAfter);
        credentials.setProperty("loginTimeout", giveUpAfter);
        credentials.setProperty("ApplicationName", "excalibase-metadata");
        this.connector = connector != null ? connector : () -> DriverManager.getConnection(url, credentials);
    }

    /** A new connection, as a read uses; the caller owns it and the transaction. */
    Connection open() throws SQLException {
        return connector.open();
    }

    private void put(String key, String value) {
        if (value != null && !value.isBlank()) {
            credentials.setProperty(key, value);
        }
    }

    /**
     * One transaction: the timeouts are {@code SET LOCAL}, so they end with it. A plain {@code SET} would be a
     * transaction of its own, and behind a transaction-mode pooler the read could land on another backend, with no
     * bound, while the timeout stuck to a backend other clients reuse. The table is created in the same
     * transaction as the read, and an empty table is reported only after the commit, so the creation is kept.
     */
    @Override
    public ReloadingMetadataSource.Raw load() throws IOException {
        try (Connection connection = connector.open()) {
            connection.setAutoCommit(false);
            boolean creating = migrate && !migrated.get();
            bound(connection);
            if (creating) {
                createTable(connection);
            }
            Optional<ReloadingMetadataSource.Raw> newest = newest(connection);
            connection.commit();
            if (creating) {
                migrated.set(true);
            }
            return newest.orElseThrow(() -> new IOException("no document has been saved yet: " + TABLE
                    + " has no row"));
        } catch (SQLException e) {
            throw new IOException(describe(e));
        }
    }

    /**
     * The server cancels a statement, or a wait for a lock, after the timeout. The socket timeout alone would only
     * make the client give up, leaving the backend waiting.
     */
    void bound(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL statement_timeout = " + queryTimeoutMillis);
            statement.execute("SET LOCAL lock_timeout = " + queryTimeoutMillis);
        }
    }

    private static void createTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(CREATE_SCHEMA);
            statement.execute(CREATE_TABLE);
        } catch (SQLException e) {
            throw new SQLException("migrate failed", "migrate:" + e.getSQLState(), null);
        }
    }

    private static Optional<ReloadingMetadataSource.Raw> newest(Connection connection)
            throws SQLException, IOException {
        try (PreparedStatement statement = connection.prepareStatement(NEWEST)) {
            statement.setInt(1, MetadataDocumentParser.MAX_BYTES);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                String text = rows.getString(2);
                if (text == null) {
                    throw new IOException("the document is larger than " + MetadataDocumentParser.MAX_BYTES
                            + " bytes");
                }
                return Optional.of(new ReloadingMetadataSource.Raw(text,
                        FileMetadataSource.sha256(text.getBytes(StandardCharsets.UTF_8))));
            }
        }
    }

    /** What an operator can act on, without anything the driver or server wrote. */
    static String describe(SQLException error) {
        String state = error.getSQLState() == null ? "" : error.getSQLState();
        if (state.startsWith("migrate:")) {
            return "metadata.db.migrate is on but the schema and table could not be created (SQLState "
                    + state.substring("migrate:".length()) + "): create " + TABLE + " yourself, or grant the right";
        }
        return switch (state) {
            case "42P01", "3F000" -> TABLE + " does not exist: create it, or set metadata.db.migrate=true";
            case "57014", "55P03" -> "the metadata database did not answer in time (the read was cancelled)";
            case "42501" -> "the metadata database user may not read " + TABLE;
            default -> otherFailure(state);
        };
    }

    private static String otherFailure(String state) {
        if (state.startsWith("28")) {
            return "the metadata database refused the credentials";
        }
        return "cannot read the metadata database (SQLState " + (state.isEmpty() ? "unknown" : state) + ")";
    }
}
