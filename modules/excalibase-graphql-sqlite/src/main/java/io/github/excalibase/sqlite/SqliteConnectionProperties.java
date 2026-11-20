package io.github.excalibase.sqlite;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What every SQLite connection of the engine starts with, as sqlite-jdbc connection properties.
 *
 * <ul>
 *   <li>{@code journal_mode=WAL}: readers never block the writer and the writer never blocks readers.</li>
 *   <li>{@code transaction_mode=IMMEDIATE}: a transaction takes the write lock when it begins, so two writers
 *       queue on the busy timeout instead of one failing at the moment it upgrades (SQLITE_BUSY at once, which
 *       no timeout helps). This is what makes the file a single writer.</li>
 *   <li>{@code busy_timeout}: how long a writer waits for the lock.</li>
 *   <li>{@code foreign_keys=true}: SQLite checks no foreign key unless asked.</li>
 *   <li>{@code case_sensitive_like=true}: LIKE is exact, as Postgres's is; ILIKE lower-cases both sides.</li>
 * </ul>
 */
public final class SqliteConnectionProperties {

    public static final int BUSY_TIMEOUT_MILLIS = 10_000;

    private SqliteConnectionProperties() {
    }

    public static Map<String, String> defaults() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("journal_mode", "WAL");
        properties.put("transaction_mode", "IMMEDIATE");
        properties.put("busy_timeout", String.valueOf(BUSY_TIMEOUT_MILLIS));
        properties.put("foreign_keys", "true");
        properties.put("case_sensitive_like", "true");
        properties.put("synchronous", "NORMAL");
        return properties;
    }
}
