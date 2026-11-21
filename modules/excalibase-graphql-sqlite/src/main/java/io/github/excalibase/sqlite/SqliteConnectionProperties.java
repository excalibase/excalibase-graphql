package io.github.excalibase.sqlite;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What every SQLite connection of the engine starts with, as sqlite-jdbc connection properties.
 *
 * <ul>
 *   <li>{@code journal_mode=WAL}: readers never block the writer and the writer never blocks readers.</li>
 *   <li>{@code transaction_mode=DEFERRED}: a read takes no lock at all. A write takes the write lock before its
 *       first statement ({@link SqliteDialect#beforeWrite}), so two writers queue on the busy timeout instead of
 *       one failing at the moment it upgrades from a read (SQLITE_BUSY at once, which no timeout helps).</li>
 *   <li>{@code busy_timeout}: how long a writer waits for the lock.</li>
 *   <li>{@code open_mode}: read and write, never create: a path with no file is an error, not an empty database.</li>
 *   <li>{@code foreign_keys=true}: SQLite checks no foreign key unless asked.</li>
 *   <li>{@code case_sensitive_like=true}: LIKE is exact, as Postgres's is; ILIKE lower-cases both sides.</li>
 *   <li>{@code synchronous}: {@code NORMAL} by default, which in WAL mode may lose the last commits (never
 *       corrupt the file) on power loss; {@code SQLITE_SYNCHRONOUS=FULL} syncs every commit.</li>
 * </ul>
 */
public final class SqliteConnectionProperties {

    public static final int BUSY_TIMEOUT_MILLIS = 10_000;
    public static final String SYNCHRONOUS_SETTING = "SQLITE_SYNCHRONOUS";

    private static final int OPEN_READ_WRITE = 0x2;
    private static final Set<String> SYNCHRONOUS_MODES = Set.of("OFF", "NORMAL", "FULL", "EXTRA");

    private SqliteConnectionProperties() {
    }

    public static Map<String, String> defaults() {
        return defaults(System.getenv(SYNCHRONOUS_SETTING));
    }

    /** @param synchronous the durability setting, or null/blank for {@code NORMAL}; anything unknown is refused */
    public static Map<String, String> defaults(String synchronous) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("journal_mode", "WAL");
        properties.put("transaction_mode", "DEFERRED");
        properties.put("busy_timeout", String.valueOf(BUSY_TIMEOUT_MILLIS));
        properties.put("open_mode", String.valueOf(OPEN_READ_WRITE));
        properties.put("foreign_keys", "true");
        properties.put("case_sensitive_like", "true");
        properties.put("synchronous", synchronousMode(synchronous));
        return properties;
    }

    private static String synchronousMode(String requested) {
        if (requested == null || requested.isBlank()) {
            return "NORMAL";
        }
        String mode = requested.strip().toUpperCase(Locale.ROOT);
        if (!SYNCHRONOUS_MODES.contains(mode)) {
            throw new IllegalArgumentException(SYNCHRONOUS_SETTING + " must be one of " + SYNCHRONOUS_MODES + ", got: "
                    + requested);
        }
        return mode;
    }
}
