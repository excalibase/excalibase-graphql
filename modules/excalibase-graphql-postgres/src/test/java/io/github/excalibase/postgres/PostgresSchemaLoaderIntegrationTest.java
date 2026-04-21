package io.github.excalibase.postgres;

import com.zaxxer.hikari.HikariDataSource;
import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises PostgresSchemaLoader against a real Postgres instance for the
 * introspection edge cases that unit tests can't cover: bit columns with
 * length, materialized views, composite types, composite foreign keys,
 * stored procedures with mixed IN/OUT/INOUT parameters, and the bulk
 * {@code loadAll} path.
 */
@Testcontainers
class PostgresSchemaLoaderIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static HikariDataSource ds;

    @BeforeAll
    static void setup() {
        ds = new HikariDataSource();
        ds.setJdbcUrl(postgres.getJdbcUrl());
        ds.setUsername(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("CREATE SCHEMA loader_test");
        jdbc.execute("CREATE TYPE loader_test.priority AS ENUM ('low','high')");
        jdbc.execute("CREATE TYPE loader_test.address AS (street text, zip varchar(10))");
        jdbc.execute("""
                CREATE TABLE loader_test.users (
                    id bigserial PRIMARY KEY,
                    email varchar(255) NOT NULL,
                    flag_bits bit(8),
                    free_bits bit varying(16),
                    priority loader_test.priority,
                    addr loader_test.address,
                    tags text[]
                )""");
        jdbc.execute("""
                CREATE TABLE loader_test.orders (
                    order_id int,
                    item_id int,
                    qty int,
                    PRIMARY KEY (order_id, item_id)
                )""");
        jdbc.execute("""
                CREATE TABLE loader_test.line_items (
                    line_id int PRIMARY KEY,
                    order_id int,
                    item_id int,
                    CONSTRAINT fk_line_order FOREIGN KEY (order_id, item_id)
                        REFERENCES loader_test.orders (order_id, item_id)
                )""");
        jdbc.execute("""
                CREATE VIEW loader_test.active_users AS
                SELECT id, email FROM loader_test.users WHERE id > 0
                """);
        jdbc.execute("""
                CREATE MATERIALIZED VIEW loader_test.user_stats AS
                SELECT id, email, length(email) AS email_len FROM loader_test.users
                """);
        jdbc.execute("""
                CREATE FUNCTION loader_test.compute_discount(qty int) RETURNS int
                LANGUAGE sql AS $$ SELECT qty * 10 $$
                """);
        jdbc.execute("""
                CREATE PROCEDURE loader_test.do_work(
                    IN work_id int,
                    INOUT counter int,
                    OUT done boolean
                ) LANGUAGE plpgsql AS $$
                BEGIN counter := counter + 1; done := true; END $$
                """);
    }

    @AfterAll
    static void tearDown() {
        if (ds != null) ds.close();
    }

    @Test
    @DisplayName("loadColumns captures bit(N) and bit varying(N) with length")
    void loadColumns_bitColumnsWithLength() {
        SchemaInfo info = new SchemaInfo();
        new PostgresSchemaLoader().loadColumns(jdbc, "loader_test", info);

        assertThat(info.getColumnType("users", "flag_bits")).isEqualTo("bit(8)");
        assertThat(info.getColumnType("users", "free_bits")).isEqualTo("bit varying(16)");
    }

    @Test
    @DisplayName("loadColumns captures enum columns via USER-DEFINED + udt_name fallback")
    void loadColumns_enumColumn() {
        SchemaInfo info = new SchemaInfo();
        new PostgresSchemaLoader().loadColumns(jdbc, "loader_test", info);

        assertThat(info.getColumnType("users", "priority")).isEqualTo("priority");
        assertThat(info.getEnumType("users", "priority")).isEqualTo("priority");
    }

    @Test
    @DisplayName("loadColumns captures array columns by udt_name")
    void loadColumns_arrayColumn() {
        SchemaInfo info = new SchemaInfo();
        new PostgresSchemaLoader().loadColumns(jdbc, "loader_test", info);

        assertThat(info.getColumnType("users", "tags")).isEqualTo("_text");
    }

    @Test
    @DisplayName("loadForeignKeys groups multi-column FKs into composite FK")
    void loadForeignKeys_compositeFk() {
        SchemaInfo info = new SchemaInfo();
        new PostgresSchemaLoader().loadColumns(jdbc, "loader_test", info);
        new PostgresSchemaLoader().loadForeignKeys(jdbc, "loader_test", info);

        // A composite FK fk_line_order(order_id, item_id) → orders(order_id, item_id)
        var fks = info.getAllForwardFks().keySet();
        assertThat(fks).isNotEmpty();
    }

    @Test
    @DisplayName("loadViews picks up regular views, materialized views, and matview columns")
    void loadViews_regularAndMaterialized() {
        SchemaInfo info = new SchemaInfo();
        new PostgresSchemaLoader().loadViews(jdbc, "loader_test", info);

        assertThat(info.getViewNames()).contains("active_users", "user_stats");
        // Matview columns should be populated
        assertThat(info.getColumns("user_stats")).contains("id", "email", "email_len");
    }

    @Test
    @DisplayName("loadCompositeTypes captures composite type fields")
    void loadCompositeTypes_captureFields() {
        SchemaInfo info = new SchemaInfo();
        new PostgresSchemaLoader().loadCompositeTypes(jdbc, "loader_test", info);

        assertThat(info.getCompositeTypes()).containsKey("address");
        assertThat(info.getCompositeTypes().get("address"))
                .extracting(SchemaInfo.CompositeTypeField::name)
                .contains("street", "zip");
    }

    @Test
    @DisplayName("loadEnums captures enum values in declared order")
    void loadEnums_capturesValues() {
        SchemaInfo info = new SchemaInfo();
        new PostgresSchemaLoader().loadEnums(jdbc, "loader_test", info);

        assertThat(info.getEnumTypes()).containsKey("priority");
        assertThat(info.getEnumTypes().get("priority")).containsExactly("low", "high");
    }

    @Test
    @DisplayName("loadStoredProcedures captures procedures with OUT/INOUT params")
    void loadStoredProcedures_captureInOutParams() {
        SchemaInfo info = new SchemaInfo();
        new PostgresSchemaLoader().loadStoredProcedures(jdbc, "loader_test", info);

        assertThat(info.getStoredProcedures()).containsKey("do_work");
        var proc = info.getStoredProcedures().get("do_work");
        assertThat(proc.inParams()).isNotEmpty();
        assertThat(proc.outParams()).isNotEmpty();
    }

    @Test
    @DisplayName("loadAll runs the bulk introspection and populates per-schema SchemaInfo maps")
    void loadAll_bulkIntrospection() {
        Map<String, SchemaInfo> perSchema = new HashMap<>();
        new PostgresSchemaLoader().loadAll(jdbc, List.of("loader_test"), perSchema);

        assertThat(perSchema).containsKey("loader_test");
        SchemaInfo info = perSchema.get("loader_test");
        assertThat(info.getTableNames()).contains("users", "orders", "line_items");
        assertThat(info.getViewNames()).contains("active_users", "user_stats");
        assertThat(info.getCompositeTypes()).containsKey("address");
        assertThat(info.getEnumTypes()).containsKey("priority");
        assertThat(info.getStoredProcedures()).containsKey("do_work");
    }

    @Test
    @DisplayName("loadAll preserves composite FKs through the bulk path")
    void loadAll_preservesCompositeFk() {
        Map<String, SchemaInfo> perSchema = new HashMap<>();
        new PostgresSchemaLoader().loadAll(jdbc, List.of("loader_test"), perSchema);

        SchemaInfo info = perSchema.get("loader_test");
        assertThat(info.getAllForwardFks()).isNotEmpty();
    }

    @Test
    @DisplayName("loadColumns on an empty schema returns an empty SchemaInfo")
    void loadColumns_emptySchema() {
        jdbc.execute("CREATE SCHEMA empty_schema");
        try {
            SchemaInfo info = new SchemaInfo();
            new PostgresSchemaLoader().loadColumns(jdbc, "empty_schema", info);

            assertThat(info.getTableNames()).isEmpty();
        } finally {
            jdbc.execute("DROP SCHEMA empty_schema CASCADE");
        }
    }
}
