package io.github.excalibase.spi;

import java.util.Map;
import java.util.ServiceLoader;

public final class QueryEngineFactory {
    private QueryEngineFactory() {}

    /** Whether a plugin on the classpath serves {@code databaseType}, a database that is not reached through SQL. */
    public static boolean serves(String databaseType) {
        if (databaseType == null || databaseType.isBlank()) {
            return false;
        }
        for (QueryEngineProvider provider : ServiceLoader.load(QueryEngineProvider.class)) {
            if (provider.supportedTypes().stream().anyMatch(type -> type.equalsIgnoreCase(databaseType.strip()))) {
                return true;
            }
        }
        return false;
    }

    public static QueryEngine create(String databaseType, Map<String, Object> settings) {
        for (QueryEngineProvider provider : ServiceLoader.load(QueryEngineProvider.class)) {
            if (provider.supportedTypes().stream().anyMatch(type -> type.equalsIgnoreCase(databaseType.strip()))) {
                return provider.create(settings);
            }
        }
        throw new IllegalArgumentException("Unsupported database type: " + databaseType
                + ". Ensure the provider module is on the classpath.");
    }
}
