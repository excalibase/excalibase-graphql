package io.github.excalibase.spi;

import io.github.excalibase.SqlEngineProvider;

import java.util.ArrayList;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

public final class SqlEngineFactory {
    private SqlEngineFactory() {}

    public static SqlEngine create(String databaseType) {
        SqlEngineProvider provider = providerFor(databaseType, installed());
        return new SqlEngine(provider.createDialect(), provider.createSchemaLoader(), provider.createMutationCompiler());
    }

    public static MutationExecutor createMutationExecutor(String databaseType) {
        return providerFor(databaseType, installed()).createMutationExecutor();
    }

    /** The driver properties the plugin of {@code databaseType} gives every pooled connection. */
    public static Map<String, String> connectionProperties(String databaseType) {
        return providerFor(databaseType, installed()).connectionProperties();
    }

    /** Lets the plugin of {@code databaseType} bound the statements of a new pooled connection. */
    public static void prepareConnection(String databaseType, Connection connection, long statementTimeoutMillis)
            throws SQLException {
        providerFor(databaseType, installed()).prepareConnection(connection, statementTimeoutMillis);
    }

    /** The schemas the plugin of {@code databaseType} always serves, or empty when they are discovered. */
    public static List<String> fixedSchemas(String databaseType) {
        return providerFor(databaseType, installed()).fixedSchemas();
    }

    /** The database types this build carries a plugin for, each by its first name. */
    public static List<String> servedTypes() {
        return servedTypes(installed());
    }

    /** Refuses a database type the plugins on the classpath do not serve, naming the image that does. */
    public static void requireServed(String databaseType) {
        requireServed(databaseType, installed());
    }

    public static List<String> servedTypes(Iterable<SqlEngineProvider> providers) {
        List<String> served = new ArrayList<>();
        for (SqlEngineProvider provider : providers) {
            served.add(provider.supportedTypes().getFirst());
        }
        return served;
    }

    public static void requireServed(String databaseType, Iterable<SqlEngineProvider> providers) {
        providerFor(databaseType, providers);
    }

    private static Iterable<SqlEngineProvider> installed() {
        return ServiceLoader.load(SqlEngineProvider.class);
    }

    private static SqlEngineProvider providerFor(String databaseType, Iterable<SqlEngineProvider> providers) {
        if (databaseType != null && !databaseType.isBlank()) {
            for (SqlEngineProvider provider : providers) {
                if (provider.supportedTypes().stream().anyMatch(t -> t.equalsIgnoreCase(databaseType.strip()))) {
                    return provider;
                }
            }
        }
        throw new IllegalArgumentException(EngineImages.refusal(String.valueOf(databaseType), servedTypes(providers)));
    }
}
