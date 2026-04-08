package io.github.excalibase;

import io.github.excalibase.postgres.PostgresSchemaLoader;
import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.zaxxer.hikari.HikariDataSource;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test: verify PostgresSchemaLoader.loadAll() loads all metadata
 * from multiple schemas in a single query (CTE + UNION ALL).
 */
@Testcontainers
class PostgresBulkIntrospectionTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-multischema.sql");

    private static JdbcTemplate jdbcTemplate;
    private static PostgresSchemaLoader loader;

    @BeforeAll
    static void setUp() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(postgres.getJdbcUrl());
        ds.setUsername(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        jdbcTemplate = new JdbcTemplate(ds);
        loader = new PostgresSchemaLoader();
    }

    // === loadAll returns both schemas ===

    @Test
    void loadAll_returnsBothSchemas_columns() {
        Map<String, SchemaInfo> perSchema = new LinkedHashMap<>();
        loader.loadAll(jdbcTemplate, List.of("schema_a", "schema_b"), perSchema);

        assertTrue(perSchema.containsKey("schema_a"), "schema_a should be loaded");
        assertTrue(perSchema.containsKey("schema_b"), "schema_b should be loaded");

        SchemaInfo a = perSchema.get("schema_a");
        SchemaInfo b = perSchema.get("schema_b");

        assertTrue(a.hasTable("users"), "schema_a should have users table");
        assertTrue(a.getColumns("users").contains("name"));
        assertTrue(a.getColumns("users").contains("status"));

        assertTrue(b.hasTable("orders"), "schema_b should have orders table");
        assertTrue(b.getColumns("orders").contains("amount"));
    }

    @Test
    void loadAll_returnsPrimaryKeys() {
        Map<String, SchemaInfo> perSchema = new LinkedHashMap<>();
        loader.loadAll(jdbcTemplate, List.of("schema_a", "schema_b"), perSchema);

        assertEquals("user_id", perSchema.get("schema_a").getPrimaryKey("users"));
        assertEquals("order_id", perSchema.get("schema_b").getPrimaryKey("orders"));
    }

    @Test
    void loadAll_returnsForeignKeys() {
        Map<String, SchemaInfo> perSchema = new LinkedHashMap<>();
        loader.loadAll(jdbcTemplate, List.of("schema_a", "schema_b"), perSchema);

        SchemaInfo b = perSchema.get("schema_b");
        // orders has FK to users (cross-schema); field name derived from FK column "user_id" → "userId"
        SchemaInfo.FkInfo fk = b.getForwardFk("orders", "userId");
        assertNotNull(fk, "orders should have FK to users");
        assertEquals("user_id", fk.fkColumn());
        assertEquals("users", fk.refTable());
        assertEquals("user_id", fk.refColumn());
    }

    @Test
    void loadAll_returnsEnums() {
        Map<String, SchemaInfo> perSchema = new LinkedHashMap<>();
        loader.loadAll(jdbcTemplate, List.of("schema_a", "schema_b"), perSchema);

        SchemaInfo a = perSchema.get("schema_a");
        Map<String, List<String>> enums = a.getEnumTypes();
        assertTrue(enums.containsKey("status_type"), "schema_a should have status_type enum");
        assertEquals(List.of("ACTIVE", "INACTIVE"), enums.get("status_type"));
    }

    @Test
    void loadAll_returnsViews() {
        Map<String, SchemaInfo> perSchema = new LinkedHashMap<>();
        loader.loadAll(jdbcTemplate, List.of("schema_a", "schema_b"), perSchema);

        SchemaInfo a = perSchema.get("schema_a");
        assertTrue(a.isView("active_users"), "active_users should be a view");
    }

    @Test
    void loadAll_excludesExtensionViews() {
        Map<String, SchemaInfo> perSchema = new LinkedHashMap<>();
        loader.loadAll(jdbcTemplate, List.of("schema_a", "schema_b"), perSchema);

        SchemaInfo a = perSchema.get("schema_a");
        // pg_stat_statements mock view should be excluded
        assertFalse(a.isView("pg_stat_statements"),
                "pg_stat_statements should be excluded");
        assertFalse(a.hasTable("pg_stat_statements"),
                "pg_stat_statements columns should not be loaded");
    }

    @Test
    void loadAll_returnsStoredProcedures() {
        Map<String, SchemaInfo> perSchema = new LinkedHashMap<>();
        loader.loadAll(jdbcTemplate, List.of("schema_a", "schema_b"), perSchema);

        SchemaInfo a = perSchema.get("schema_a");
        Map<String, SchemaInfo.ProcedureInfo> procs = a.getStoredProcedures();
        assertTrue(procs.containsKey("reset_user"), "schema_a should have reset_user proc");
    }

    // === Single schema still works ===

    @Test
    void loadAll_singleSchema_works() {
        Map<String, SchemaInfo> perSchema = new LinkedHashMap<>();
        loader.loadAll(jdbcTemplate, List.of("schema_a"), perSchema);

        assertEquals(1, perSchema.size());
        assertTrue(perSchema.get("schema_a").hasTable("users"));
    }

    // === Empty schema list doesn't crash ===

    @Test
    void loadAll_emptySchemaList_doesNotCrash() {
        Map<String, SchemaInfo> perSchema = new LinkedHashMap<>();
        assertDoesNotThrow(() -> loader.loadAll(jdbcTemplate, List.of(), perSchema));
        assertTrue(perSchema.isEmpty());
    }
}
