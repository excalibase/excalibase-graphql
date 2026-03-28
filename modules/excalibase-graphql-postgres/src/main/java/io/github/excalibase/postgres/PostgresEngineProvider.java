package io.github.excalibase.postgres;

import io.github.excalibase.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

public class PostgresEngineProvider implements SqlEngineProvider {
    @Override
    public List<String> supportedTypes() {
        return List.of("postgres", "postgresql");
    }

    @Override
    public SqlDialect createDialect() {
        return new PostgresDialect();
    }

    @Override
    public SchemaLoader createSchemaLoader() {
        return new PostgresSchemaLoader();
    }

    @Override
    public MutationCompiler createMutationCompiler() {
        return new PostgresMutationCompiler();
    }

    @Override
    public MutationExecutor createMutationExecutor(JdbcTemplate jdbcTemplate, TransactionTemplate txTemplate) {
        return new PostgresMutationExecutor();
    }
}
