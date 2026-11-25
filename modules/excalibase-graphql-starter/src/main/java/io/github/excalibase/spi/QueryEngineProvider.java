package io.github.excalibase.spi;

import java.util.List;
import java.util.Map;

/**
 * A database that is not reached through SQL and JDBC. Sits beside {@link io.github.excalibase.SqlEngineProvider},
 * which stays as it is; registers through {@code META-INF/services/io.github.excalibase.spi.QueryEngineProvider}.
 */
public interface QueryEngineProvider {

    List<String> supportedTypes();

    /**
     * @param settings the provider's own configuration (connection, declared collections, permissions)
     * @throws IllegalStateException when the configuration cannot be served safely; the engine refuses to start
     */
    QueryEngine create(Map<String, Object> settings);
}
