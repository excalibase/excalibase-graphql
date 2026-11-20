package io.github.excalibase.sqlite;

import io.github.excalibase.SqlDialect;
import io.github.excalibase.SqlEngineProvider;
import io.github.excalibase.spi.MutationCompiler;
import io.github.excalibase.spi.MutationExecutor;
import io.github.excalibase.spi.SchemaLoader;

import java.util.List;

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
}
