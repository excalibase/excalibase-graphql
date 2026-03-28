package io.github.excalibase.mysql;

import io.github.excalibase.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;

public class MysqlEngineProvider implements SqlEngineProvider {
    @Override
    public List<String> supportedTypes() {
        return List.of("mysql");
    }

    @Override
    public SqlDialect createDialect() {
        return new MysqlDialect();
    }

    @Override
    public SchemaLoader createSchemaLoader() {
        return new MysqlSchemaLoader();
    }

    @Override
    public MutationCompiler createMutationCompiler() {
        return new MysqlMutationCompiler();
    }

    @Override
    public MutationExecutor createMutationExecutor(JdbcTemplate jdbcTemplate, TransactionTemplate txTemplate) {
        return new MysqlMutationExecutor(jdbcTemplate, txTemplate);
    }
}
