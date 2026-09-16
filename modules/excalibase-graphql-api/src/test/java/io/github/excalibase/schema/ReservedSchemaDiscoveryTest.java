package io.github.excalibase.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves the reserved-schema denylist is enforced at the discovery layer, so nothing
 * downstream (type generation, REST, introspection, realtime) ever sees those tables.
 */
class ReservedSchemaDiscoveryTest {

    private static final List<String> RAW_SCHEMAS =
            List.of("auth", "excalibase", "public", "hana", "kanban", "rls_demo");

    private GraphqlSchemaManager managerFor(String databaseType, String reservedSchemasConfig,
                                            JdbcTemplate jdbcTemplate) {
        return new GraphqlSchemaManager(
                jdbcTemplate,
                mock(TransactionTemplate.class),
                30,
                databaseType,
                GraphqlSchemaManager.DEFAULT_MAX_QUERY_DEPTH,
                30,
                reservedSchemasConfig,
                null,
                null);
    }

    private JdbcTemplate jdbcReturning(List<String> schemas) {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class))).thenReturn(schemas);
        return jdbcTemplate;
    }

    @Test
    @DisplayName("discoverSchemas excludes built-in reserved schemas on postgres")
    void discoverSchemas_postgresWithReservedSchemas_excludesThem() {
        JdbcTemplate jdbcTemplate = jdbcReturning(RAW_SCHEMAS);

        List<String> discovered = managerFor("postgres", "", jdbcTemplate).discoverSchemas(jdbcTemplate);

        assertThat(discovered).containsExactly("public", "hana", "kanban", "rls_demo");
    }

    @Test
    @DisplayName("discoverSchemas excludes built-in reserved schemas on mysql")
    void discoverSchemas_mysqlWithReservedSchemas_excludesThem() {
        JdbcTemplate jdbcTemplate = jdbcReturning(RAW_SCHEMAS);

        List<String> discovered = managerFor("mysql", "", jdbcTemplate).discoverSchemas(jdbcTemplate);

        assertThat(discovered).doesNotContain("auth", "excalibase")
                .containsExactly("public", "hana", "kanban", "rls_demo");
    }

    @Test
    @DisplayName("discoverSchemas also excludes schemas added through configuration")
    void discoverSchemas_configuredExtraSchema_excludesIt() {
        JdbcTemplate jdbcTemplate = jdbcReturning(RAW_SCHEMAS);

        List<String> discovered = managerFor("postgres", "kanban", jdbcTemplate).discoverSchemas(jdbcTemplate);

        assertThat(discovered).containsExactly("public", "hana", "rls_demo");
    }

    @Test
    @DisplayName("discoverSchemas keeps built-ins excluded even when configuration omits them")
    void discoverSchemas_configurationWithoutBuiltIns_stillExcludesBuiltIns() {
        JdbcTemplate jdbcTemplate = jdbcReturning(RAW_SCHEMAS);

        List<String> discovered = managerFor("postgres", "billing", jdbcTemplate).discoverSchemas(jdbcTemplate);

        // Asserting the survivors too, so this cannot pass on an empty list.
        assertThat(discovered).containsExactly("public", "hana", "kanban", "rls_demo");
    }

    @Test
    @DisplayName("discoverSchemas keeps ordinary tenant schemas reflected")
    void discoverSchemas_tenantSchemas_remainVisible() {
        JdbcTemplate jdbcTemplate = jdbcReturning(List.of("public", "shopify", "clinic", "complex", "nosql"));

        List<String> discovered = managerFor("postgres", "", jdbcTemplate).discoverSchemas(jdbcTemplate);

        assertThat(discovered).containsExactly("public", "shopify", "clinic", "complex", "nosql");
    }
}
