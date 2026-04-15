package io.github.excalibase.postgres;

import com.zaxxer.hikari.HikariDataSource;
import graphql.language.ArrayValue;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import graphql.language.StringValue;
import graphql.language.Value;
import io.github.excalibase.SqlDialect;
import io.github.excalibase.compiler.FilterBuilder;
import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end verification that {@link FilterBuilder}'s JSONB operators
 * produce SQL that executes correctly against a real Postgres jsonb column.
 *
 * <p>These tests exercise the full {@code FilterBuilder → NamedParameterJdbcTemplate}
 * path, the same one production data fetchers use. Written RED against the
 * missing {@code JsonFilterInput} surface — after the new dialect methods +
 * filter builder cases + schema advertisement land, every test here goes
 * green.
 */
@Testcontainers
class JsonFilterIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static NamedParameterJdbcTemplate named;
    private static HikariDataSource ds;

    private final SqlDialect dialect = new PostgresDialect();

    @BeforeAll
    static void setupDb() {
        ds = new HikariDataSource();
        ds.setJdbcUrl(postgres.getJdbcUrl());
        ds.setUsername(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        named = new NamedParameterJdbcTemplate(ds);

        jdbc.execute("""
            CREATE SCHEMA IF NOT EXISTS jf;
            CREATE TABLE jf.items (
              id        bigserial PRIMARY KEY,
              name      text NOT NULL,
              metadata  jsonb NOT NULL
            );
            """);
        jdbc.update("""
            INSERT INTO jf.items (name, metadata) VALUES
              ('Alpha',   '{"color":"red","size":"L","stock":10}'::jsonb),
              ('Beta',    '{"color":"blue","size":"M","stock":0}'::jsonb),
              ('Gamma',   '{"color":"red","size":"S","weight":3}'::jsonb),
              ('Delta',   '{"color":"green","weight":5}'::jsonb),
              ('Epsilon', '{"tags":["promoted"],"size":"L"}'::jsonb)
            """);
    }

    @AfterAll
    static void tearDown() {
        if (ds != null) ds.close();
    }

    private SchemaInfo schemaInfoWithTable() {
        SchemaInfo info = new SchemaInfo();
        info.addColumn("items", "id", "bigint");
        info.addColumn("items", "name", "text");
        info.addColumn("items", "metadata", "jsonb");
        info.setTableSchema("items", "jf");
        return info;
    }

    private ObjectValue whereClause(String column, String operator, Value<?> operand) {
        ObjectValue inner = ObjectValue.newObjectValue()
                .objectField(new ObjectField(operator, operand))
                .build();
        return ObjectValue.newObjectValue()
                .objectField(new ObjectField(column, inner))
                .build();
    }

    /**
     * Execute a FilterBuilder-built WHERE clause against the seeded items
     * table and return the names in id order.
     */
    private List<String> execute(String column, String operator, Value<?> operand) {
        SchemaInfo info = schemaInfoWithTable();
        FilterBuilder fb = new FilterBuilder(dialect, 100, info, "jf");

        Map<String, Object> params = new HashMap<>();
        List<String> conditions = new ArrayList<>();
        fb.buildFilterConditions(whereClause(column, operator, operand), "i", params, conditions, "items");

        assertFalse(conditions.isEmpty(),
                "FilterBuilder must emit a condition for operator " + operator);
        String where = String.join(" AND ", conditions);
        String sql = "SELECT i.name FROM jf.items i WHERE " + where + " ORDER BY i.id";

        MapSqlParameterSource ps = new MapSqlParameterSource();
        params.forEach(ps::addValue);
        return named.queryForList(sql, ps, String.class);
    }

    private ArrayValue stringList(String... values) {
        ArrayValue.Builder b = ArrayValue.newArrayValue();
        for (String v : values) b.value(new StringValue(v));
        return b.build();
    }

    // === hasKey / hasKeys / hasAnyKeys ===

    @Test
    @DisplayName("hasKey: metadata ? 'color' — Alpha, Beta, Gamma, Delta")
    void hasKeyColor() {
        List<String> hits = execute("metadata", "hasKey", new StringValue("color"));
        assertEquals(List.of("Alpha", "Beta", "Gamma", "Delta"), hits);
    }

    @Test
    @DisplayName("hasKey: metadata ? 'tags' — only Epsilon")
    void hasKeyTags() {
        List<String> hits = execute("metadata", "hasKey", new StringValue("tags"));
        assertEquals(List.of("Epsilon"), hits);
    }

    @Test
    @DisplayName("hasKey: non-existent key returns empty")
    void hasKeyNoMatch() {
        List<String> hits = execute("metadata", "hasKey", new StringValue("nonexistent"));
        assertTrue(hits.isEmpty(), "no row has the 'nonexistent' key");
    }

    @Test
    @DisplayName("hasKeys: metadata ?& '{color,size}' — Alpha, Beta, Gamma have BOTH")
    void hasKeysBoth() {
        // Delta has color but no size, Epsilon has size but no color — excluded
        List<String> hits = execute("metadata", "hasKeys", stringList("color", "size"));
        assertEquals(List.of("Alpha", "Beta", "Gamma"), hits);
    }

    @Test
    @DisplayName("hasAnyKeys: metadata ?| '{weight,tags}' — Gamma, Delta, Epsilon")
    void hasAnyKeysOneOf() {
        List<String> hits = execute("metadata", "hasAnyKeys", stringList("weight", "tags"));
        assertEquals(List.of("Gamma", "Delta", "Epsilon"), hits);
    }

    // === contains / containedBy (JSONB @> and <@) ===

    @Test
    @DisplayName("contains: metadata @> '{\"color\":\"red\"}' — Alpha and Gamma")
    void containsRed() {
        ObjectValue json = ObjectValue.newObjectValue()
                .objectField(new ObjectField("color", new StringValue("red")))
                .build();
        List<String> hits = execute("metadata", "contains", json);
        assertEquals(List.of("Alpha", "Gamma"), hits);
    }

    @Test
    @DisplayName("contains with multiple keys: must match all — Alpha only")
    void containsMultiKey() {
        ObjectValue json = ObjectValue.newObjectValue()
                .objectField(new ObjectField("color", new StringValue("red")))
                .objectField(new ObjectField("size", new StringValue("L")))
                .build();
        List<String> hits = execute("metadata", "contains", json);
        assertEquals(List.of("Alpha"), hits);
    }

    @Test
    @DisplayName("contains with no match returns empty")
    void containsNoMatch() {
        ObjectValue json = ObjectValue.newObjectValue()
                .objectField(new ObjectField("color", new StringValue("purple")))
                .build();
        List<String> hits = execute("metadata", "contains", json);
        assertTrue(hits.isEmpty(), "no row has color=purple");
    }

    @Test
    @DisplayName("containedBy: metadata <@ a superset — Delta fits inside its own spec + extras")
    void containedByWithSuperset() {
        // Delta's full metadata is {"color":"green","weight":5}. A superset
        // containing those keys + extras should match Delta only.
        ObjectValue json = ObjectValue.newObjectValue()
                .objectField(new ObjectField("color", new StringValue("green")))
                .objectField(new ObjectField("weight", new graphql.language.IntValue(java.math.BigInteger.valueOf(5))))
                .objectField(new ObjectField("extra", new StringValue("x")))
                .build();
        List<String> hits = execute("metadata", "containedBy", json);
        assertEquals(List.of("Delta"), hits);
    }
}
