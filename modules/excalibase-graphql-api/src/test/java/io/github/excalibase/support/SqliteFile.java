package io.github.excalibase.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/** A SQLite database in a temp file, the way a tiny project keeps one, and the engine settings that serve it. */
public final class SqliteFile {

    /** Customers, their orders and a table with a composite key: enough to reach every kind of relationship. */
    public static final String SHOP = """
            CREATE TABLE customers (
                id INTEGER PRIMARY KEY, name TEXT NOT NULL, email TEXT UNIQUE, active BOOLEAN NOT NULL DEFAULT 1,
                balance NUMERIC, created_at TIMESTAMPTZ, joined DATE, meta JSON, visits BIGINT);
            CREATE TABLE orders (
                id INTEGER PRIMARY KEY, customer_id INTEGER NOT NULL REFERENCES customers(id),
                total REAL, status TEXT, note TEXT);
            CREATE TABLE tags (order_id INTEGER NOT NULL REFERENCES orders(id), tag TEXT NOT NULL,
                PRIMARY KEY (order_id, tag));
            CREATE TABLE events (id INTEGER PRIMARY KEY, at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, label TEXT);
            INSERT INTO events (id, at, label) VALUES (1, '2026-01-01 10:00:00', 'early'), (2, '2026-01-01 12:00:00', 'late');
            INSERT INTO customers VALUES
                (1, 'Alice', 'alice@x.io', 1, 10.5, '2026-01-01T10:00:00.000000Z', '2026-01-15', '{"vip":true}', 5),
                (2, 'Bob', 'bob@x.io', 0, 0, '2026-02-01T00:00:00.000000Z', '2026-02-01', NULL, 9007199254740993),
                (3, 'carol', NULL, 1, NULL, NULL, NULL, '[1,2]', NULL);
            INSERT INTO orders VALUES (1, 1, 9.99, 'open', 'first'), (2, 1, 20, 'closed', NULL), (3, 2, 5.5, 'open', 'a%b');
            INSERT INTO tags VALUES (1, 'x'), (1, 'y'), (2, 'x');
            """;

    private final Path path;

    private SqliteFile(Path path) {
        this.path = path;
    }

    public static SqliteFile create(String script) {
        try {
            Path file = Files.createTempFile("excalibase-sqlite-", ".db");
            file.toFile().deleteOnExit();
            SqliteFile created = new SqliteFile(file);
            created.run(script);
            return created;
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public Path path() {
        return path;
    }

    public String url() {
        return "jdbc:sqlite:" + path;
    }

    /** Runs {@code script} on its own connection, one statement at a time. */
    public void run(String script) {
        try (Connection connection = DriverManager.getConnection(url()); Statement statement = connection.createStatement()) {
            for (String sql : script.split(";\\s*\n")) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** The settings that serve this file as project {@code projectId}, with no authentication. */
    public Map<String, String> engine(String projectId) {
        Map<String, String> properties = new HashMap<>(EngineProperties.isolated());
        properties.put("spring.datasource.url", url());
        properties.put("spring.datasource.driver-class-name", "org.sqlite.JDBC");
        properties.put("app.project-id", projectId);
        properties.put("app.database-type", "sqlite");
        properties.put("app.security.jwt-enabled", "false");
        properties.put("app.security.insecure-dev-mode", "true");
        properties.put("app.max-rows", "30");
        return properties;
    }
}
