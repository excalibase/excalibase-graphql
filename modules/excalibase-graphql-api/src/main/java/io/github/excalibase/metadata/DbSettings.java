package io.github.excalibase.metadata;

import java.time.Duration;

/**
 * What the {@code db} source is configured with.
 *
 * @param url      the metadata database's JDBC URL (env {@code METADATA_DATABASE_URL}); blank means the served database
 * @param username its user; blank when the URL is blank, since the served database's credentials are used
 * @param password its password; never logged, and never part of an error
 * @param queryTimeout the server-side bound on one statement and on waiting for a lock, so a hung read is cancelled
 *                     on the server and leaves no backend behind
 * @param migrate  whether the engine may create the schema and table; off, the table must already exist
 */
public record DbSettings(String url, String username, String password, boolean migrate, Duration pollInterval,
                         Duration connectTimeout, Duration queryTimeout) {

    public static final Duration DEFAULT_QUERY_TIMEOUT = Duration.ofSeconds(10);

    public DbSettings(String url, String username, String password, boolean migrate, Duration pollInterval,
                      Duration connectTimeout) {
        this(url, username, password, migrate, pollInterval, connectTimeout, DEFAULT_QUERY_TIMEOUT);
    }

    public static final DbSettings NONE = new DbSettings("", "", "", false, Duration.ofSeconds(5),
            Duration.ofSeconds(3), DEFAULT_QUERY_TIMEOUT);

    @Override
    public String toString() {
        return "DbSettings[url and credentials withheld]";
    }
}
