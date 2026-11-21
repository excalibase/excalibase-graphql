package io.github.excalibase.sqlite;

import io.github.excalibase.SqlDialect;
import io.github.excalibase.SqlEngineProvider;
import io.github.excalibase.spi.MutationCompiler;
import io.github.excalibase.spi.MutationExecutor;
import io.github.excalibase.spi.SchemaLoader;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

public class SqliteEngineProvider implements SqlEngineProvider {
    @Override
    public List<String> supportedTypes() {
        return List.of("sqlite");
    }

    @Override
    public SqlDialect createDialect() {
        return new SqliteDialect();
    }

    @Override
    public SchemaLoader createSchemaLoader() {
        return new SqliteSchemaLoader();
    }

    @Override
    public MutationCompiler createMutationCompiler() {
        return new SqliteMutationCompiler();
    }

    @Override
    public MutationExecutor createMutationExecutor() {
        return new SqliteMutationExecutor();
    }

    @Override
    public Map<String, String> connectionProperties() {
        return SqliteConnectionProperties.defaults();
    }

    @Override
    public List<String> fixedSchemas() {
        return List.of(SqliteSchemaLoader.MAIN);
    }

    @Override
    public void prepareConnection(Connection connection, long statementTimeoutMillis) throws SQLException {
        StatementDeadline.install(connection, statementTimeoutMillis);
    }
}
