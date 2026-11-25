package io.github.excalibase.spi;

import java.util.Map;
import java.util.ServiceLoader;

public final class QueryEngineFactory {
    private QueryEngineFactory() {}

    public static QueryEngine create(String databaseType, Map<String, Object> settings) {
        for (QueryEngineProvider provider : ServiceLoader.load(QueryEngineProvider.class)) {
            if (provider.supportedTypes().stream().anyMatch(type -> type.equalsIgnoreCase(databaseType))) {
                return provider.create(settings);
            }
        }
        throw new IllegalArgumentException("Unsupported database type: " + databaseType
                + ". Ensure the provider module is on the classpath.");
    }
}
