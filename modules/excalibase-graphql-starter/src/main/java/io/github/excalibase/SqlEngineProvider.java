package io.github.excalibase;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;

/**
 * SPI interface for database engine providers.
 * Implementations register via META-INF/services/io.github.excalibase.SqlEngineProvider.
 */
public interface SqlEngineProvider {
    List<String> supportedTypes();
    SqlDialect createDialect();
    SchemaLoader createSchemaLoader();
    MutationCompiler createMutationCompiler();
    MutationExecutor createMutationExecutor(JdbcTemplate jdbcTemplate, TransactionTemplate txTemplate);
}
