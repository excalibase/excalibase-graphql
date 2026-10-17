package io.github.excalibase.spi;

import io.github.excalibase.SqlEngineProvider;

import java.util.ServiceLoader;

public final class SqlEngineFactory {
    private SqlEngineFactory() {}

    public static SqlEngine create(String databaseType) {
        for (SqlEngineProvider provider : ServiceLoader.load(SqlEngineProvider.class)) {
            if (provider.supportedTypes().stream().anyMatch(t -> t.equalsIgnoreCase(databaseType))) {
                return new SqlEngine(provider.createDialect(), provider.createSchemaLoader(), provider.createMutationCompiler());
            }
        }
        throw new IllegalArgumentException("Unsupported database type: " + databaseType
                + ". Ensure the dialect module is on the classpath.");
    }

    public static MutationExecutor createMutationExecutor(String databaseType) {
        for (SqlEngineProvider provider : ServiceLoader.load(SqlEngineProvider.class)) {
            if (provider.supportedTypes().stream().anyMatch(t -> t.equalsIgnoreCase(databaseType))) {
                return provider.createMutationExecutor();
            }
        }
        throw new IllegalArgumentException("Unsupported database type: " + databaseType);
    }
}
