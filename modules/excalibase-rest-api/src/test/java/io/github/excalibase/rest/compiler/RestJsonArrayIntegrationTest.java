package io.github.excalibase.rest.compiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.github.excalibase.SqlDialect;
import io.github.excalibase.postgres.PostgresDialect;
import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end verification that {@link RestQueryCompiler}'s JSON and array
 * operators produce SQL that actually executes against real Postgres. The
 * unit tests at {@link RestQueryCompilerTest} only check the SQL string shape
 * — that passes even when the bind values have the wrong type and Postgres
 * rejects the query at runtime. This test seeds a real table and runs every
 * operator through NamedParameterJdbcTemplate, the same path the REST
 * controller uses in production.
 *
 * <p>When I first wrote this suite, 5 operators were broken in integration
 * even though their unit tests passed:
 * <ul>
 *   <li>{@code arraycontains} / {@code arrayhasall} — emitted {@code ARRAY[$1]}
 *       which bound as {@code varchar[]} on Postgres while the column was
 *       {@code text[]} — type mismatch</li>
 *   <li>{@code arrayhasany} / {@code ov} — bound the raw {@code {a,b}} String
 *       with no Postgres type coercion</li>
 *   <li>{@code jsonpathexists} — emitted the {@code @@} tsvector-match
 *       operator instead of {@code @?} (jsonpath-exists)</li>
 * </ul>
 * Every test here was written RED against those bugs before the fix landed.
 */
@Testcontainers
class RestJsonArrayIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static NamedParameterJdbcTemplate named;
    private static HikariDataSource ds;
    private static RestQueryCompiler compiler;

    @BeforeAll
    static void setupDb() {
        ds = new HikariDataSource();
        ds.setJdbcUrl(postgres.getJdbcUrl());
        ds.setUsername(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        named = new NamedParameterJdbcTemplate(ds);

        jdbc.execute("""
            CREATE SCHEMA IF NOT EXISTS rja;
            CREATE TABLE rja.items (
              id        bigserial PRIMARY KEY,
              name      text NOT NULL,
              metadata  jsonb NOT NULL,
              tags      text[] NOT NULL
            );
            """);
        jdbc.update("""
            INSERT INTO rja.items (name, metadata, tags) VALUES
              ('Alpha',   '{"color":"red","size":"L","stock":10}'::jsonb, ARRAY['new','featured']),
              ('Beta',    '{"color":"blue","size":"M","stock":0}'::jsonb, ARRAY['clearance']),
              ('Gamma',   '{"color":"red","size":"S"}'::jsonb,            ARRAY['new','limited','featured']),
              ('Delta',   '{"color":"green","weight":5}'::jsonb,          ARRAY['featured']),
              ('Epsilon', '{"tags":["promoted"]}'::jsonb,                 ARRAY['clearance','limited'])
            """);

        SchemaInfo schema = new SchemaInfo();
        schema.addColumn("items", "id", "bigint");
        schema.addColumn("items", "name", "text");
        schema.addColumn("items", "metadata", "jsonb");
        schema.addColumn("items", "tags", "_text");
        schema.addPrimaryKey("items", "id");
        schema.setTableSchema("items", "rja");

        SqlDialect dialect = new PostgresDialect();
        compiler = new RestQueryCompiler(schema, dialect, "rja", 100);
    }

    @AfterAll
    static void tearDown() {
        if (ds != null) ds.close();
    }

    @SuppressWarnings("unchecked")
    private List<String> namesOf(RestQueryCompiler.CompiledResult r) {
        MapSqlParameterSource ps = new MapSqlParameterSource();
        r.params().forEach(ps::addValue);
        Object result = named.queryForObject(r.sql(), ps, Object.class);
        try {
            var mapper = new ObjectMapper();
            Object parsed = mapper.readValue(result == null ? "[]" : result.toString(), Object.class);
            List<Map<String, Object>> rows;
            if (parsed instanceof List<?> list) {
                rows = (List<Map<String, Object>>) list;
            } else if (parsed instanceof Map<?, ?> map) {
                Object body = map.get("items");
                if (body instanceof List<?> list) {
                    rows = (List<Map<String, Object>>) list;
                } else {
                    return List.of();
                }
            } else {
                return List.of();
            }
            return rows.stream().map(row -> (String) row.get("name")).sorted().toList();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private RestQueryCompiler.CompiledResult filter(String col, String op, String value) {
        var filters = List.of(new RestQueryCompiler.FilterSpec(col, op, value, false));
        return compiler.compileSelect("items", List.of("id", "name"), filters, null, 100, 0, false);
    }

    // === JSONB operators ===

    @Test
    @DisplayName("haskey: metadata ? 'color' — Alpha, Beta, Gamma, Delta have color")
    void haskey() {
        List<String> hits = namesOf(filter("metadata", "haskey", "color"));
        assertEquals(List.of("Alpha", "Beta", "Delta", "Gamma"), hits);
    }

    @Test
    @DisplayName("jsoncontains: metadata @> '{\"color\":\"red\"}'::jsonb — Alpha and Gamma")
    void jsoncontainsRed() {
        List<String> hits = namesOf(filter("metadata", "jsoncontains", "{\"color\":\"red\"}"));
        assertEquals(List.of("Alpha", "Gamma"), hits);
    }

    @Test
    @DisplayName("cs (alias for jsoncontains): same result")
    void csAlias() {
        List<String> hits = namesOf(filter("metadata", "cs", "{\"color\":\"red\"}"));
        assertEquals(List.of("Alpha", "Gamma"), hits);
    }

    @Test
    @DisplayName("jsoncontained: metadata <@ a superset — Gamma fits inside a full spec")
    void jsoncontainedBy() {
        // {"color":"red","size":"S","extra":"x"} contains Gamma's full metadata
        List<String> hits = namesOf(filter("metadata", "jsoncontained",
                "{\"color\":\"red\",\"size\":\"S\",\"extra\":\"x\"}"));
        assertEquals(List.of("Gamma"), hits);
    }

    @Test
    @DisplayName("jsonpath: metadata @? '$.color' — rows where the $.color path returns an item")
    void jsonpathReturnsMatchingRows() {
        List<String> hits = namesOf(filter("metadata", "jsonpath", "$.color"));
        // Alpha, Beta, Gamma, Delta have a color key — Epsilon does not
        assertEquals(List.of("Alpha", "Beta", "Delta", "Gamma"), hits);
    }

    @Test
    @DisplayName("jsonpathexists: same semantic as jsonpath (both use the @? operator)")
    void jsonpathexistsReturnsMatchingRows() {
        // This was previously broken — emitted @@ (tsvector match) instead of @?.
        List<String> hits = namesOf(filter("metadata", "jsonpathexists", "$.stock"));
        // Only Alpha + Beta have $.stock
        assertEquals(List.of("Alpha", "Beta"), hits);
    }

    // === Array operators ===

    @Test
    @DisplayName("arraycontains: tags @> '{new}' — Alpha and Gamma have 'new'")
    void arraycontainsSingle() {
        // Previously broken: emitted ARRAY[$1] which bound as varchar[], didn't match text[].
        List<String> hits = namesOf(filter("tags", "arraycontains", "{new}"));
        assertEquals(List.of("Alpha", "Gamma"), hits);
    }

    @Test
    @DisplayName("arraycontains with multi-element set: tags @> '{new,featured}' — Alpha and Gamma have both")
    void arraycontainsMultiElement() {
        List<String> hits = namesOf(filter("tags", "arraycontains", "{new,featured}"));
        assertEquals(List.of("Alpha", "Gamma"), hits);
    }

    @Test
    @DisplayName("arrayhasall is an alias for arraycontains")
    void arrayhasallIsContains() {
        List<String> hits = namesOf(filter("tags", "arrayhasall", "{clearance,limited}"));
        assertEquals(List.of("Epsilon"), hits);
    }

    @Test
    @DisplayName("arrayhasany: tags && '{clearance,limited}' — Beta, Epsilon, Gamma match")
    void arrayhasanyOverlap() {
        // Previously broken: bound the raw '{clearance,limited}' string with no type coercion.
        List<String> hits = namesOf(filter("tags", "arrayhasany", "{clearance,limited}"));
        assertEquals(List.of("Beta", "Epsilon", "Gamma"), hits);
    }

    @Test
    @DisplayName("ov (alias for arrayhasany): same result")
    void ovAlias() {
        List<String> hits = namesOf(filter("tags", "ov", "{clearance,limited}"));
        assertEquals(List.of("Beta", "Epsilon", "Gamma"), hits);
    }

    @Test
    @DisplayName("arraylength: 2-element tag lists — Alpha and Epsilon")
    void arraylengthTwo() {
        List<String> hits = namesOf(filter("tags", "arraylength", "2"));
        assertEquals(List.of("Alpha", "Epsilon"), hits);
    }

    @Test
    @DisplayName("arraylength: 3-element tag list — only Gamma")
    void arraylengthThree() {
        List<String> hits = namesOf(filter("tags", "arraylength", "3"));
        assertEquals(List.of("Gamma"), hits);
    }

    // === Falsifiability checks — operators must not silently return all rows ===

    @Test
    @DisplayName("jsoncontains with no matches returns empty, not all rows")
    void jsoncontainsNoMatch() {
        List<String> hits = namesOf(filter("metadata", "jsoncontains", "{\"color\":\"purple\"}"));
        assertTrue(hits.isEmpty(), "no row has color=purple");
    }

    @Test
    @DisplayName("arraycontains with no matches returns empty, not all rows")
    void arraycontainsNoMatch() {
        List<String> hits = namesOf(filter("tags", "arraycontains", "{nonexistent}"));
        assertTrue(hits.isEmpty(), "no row has tag 'nonexistent'");
    }
}
