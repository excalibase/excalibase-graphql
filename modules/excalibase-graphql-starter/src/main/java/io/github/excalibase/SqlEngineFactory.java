package io.github.excalibase;

import java.util.ServiceLoader;

public final class SqlEngineFactory {
    private SqlEngineFactory() {}

    public static SqlEngine create(String databaseType) {
        for (SqlEngineProvider p : ServiceLoader.load(SqlEngineProvider.class)) {
            if (p.supportedTypes().stream().anyMatch(t -> t.equalsIgnoreCase(databaseType))) {
                return new SqlEngine(p.createDialect(), p.createSchemaLoader(), p.createMutationCompiler());
            }
        }
        throw new IllegalArgumentException("Unsupported database type: " + databaseType
                + ". Ensure the dialect module is on the classpath.");
    }

    public static MutationExecutor createMutationExecutor(String databaseType,
            org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
            org.springframework.transaction.support.TransactionTemplate txTemplate) {
        for (SqlEngineProvider p : ServiceLoader.load(SqlEngineProvider.class)) {
            if (p.supportedTypes().stream().anyMatch(t -> t.equalsIgnoreCase(databaseType))) {
                return p.createMutationExecutor(jdbcTemplate, txTemplate);
            }
        }
        throw new IllegalArgumentException("Unsupported database type: " + databaseType);
    }
}
