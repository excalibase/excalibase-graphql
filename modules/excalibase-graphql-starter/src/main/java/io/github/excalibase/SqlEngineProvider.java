package io.github.excalibase;

import io.github.excalibase.spi.MutationCompiler;
import io.github.excalibase.spi.MutationExecutor;
import io.github.excalibase.spi.SchemaLoader;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * SPI interface for database engine providers.
 * Implementations register via META-INF/services/io.github.excalibase.SqlEngineProvider.
 */
public interface SqlEngineProvider {
    List<String> supportedTypes();
    SqlDialect createDialect();
    SchemaLoader createSchemaLoader();
    MutationCompiler createMutationCompiler();
    MutationExecutor createMutationExecutor();

    /** Driver properties every pooled connection of this database starts with; none for a server database. */
    default Map<String, String> connectionProperties() {
        return Map.of();
    }

    /**
     * Runs on every new pooled connection, for a database whose connection settings are not SQL a pool can send: the
     * plugin bounds each statement to {@code statementTimeoutMillis} here. A server database sets it in session SQL.
     */
    default void prepareConnection(Connection connection, long statementTimeoutMillis) throws SQLException {
        // nothing to prepare
    }

    /** The schemas this database always serves, when it has no catalogue to list them from; empty to discover them. */
    default List<String> fixedSchemas() {
        return List.of();
    }
}
