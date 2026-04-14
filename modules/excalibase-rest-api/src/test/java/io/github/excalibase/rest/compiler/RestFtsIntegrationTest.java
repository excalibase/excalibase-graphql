package io.github.excalibase.rest.compiler;

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
 * End-to-end verification that {@link RestQueryCompiler}'s PostgREST-style
 * FTS operators ({@code fts}, {@code plfts}, {@code phfts}, {@code wfts})
 * produce SQL that executes correctly against a real Postgres.
 *
 * <p>The unit test at {@code RestQueryCompilerTest} asserts the SQL string
 * contains the right tsquery function name. This test goes the extra step:
 * seeds a real table, runs the assembled SQL via NamedParameterJdbcTemplate,
 * and asserts the expected rows come back. It's the equivalent of the
 * GraphQL-side {@code FtsIntegrationTest} but for the REST compile path.
 */
@Testcontainers
class RestFtsIntegrationTest {

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
            CREATE SCHEMA IF NOT EXISTS rfts;
            CREATE TABLE rfts.articles (
              id    bigserial PRIMARY KEY,
              title text NOT NULL,
              body  text NOT NULL
            );
            """);
        jdbc.update("""
            INSERT INTO rfts.articles (title, body) VALUES
              ('Postgres tips',             'Indexing strategies for large tables'),
              ('Kubernetes operators',      'Writing custom controllers with the operator SDK'),
              ('Database performance',      'Query tuning, explain plans, and partial indexes'),
              ('Go concurrency',            'Channels, contexts, and cancellation'),
              ('Full-text search intro',    'tsvector, plainto_tsquery, and GIN indexes')
            """);

        // Build the SchemaInfo by hand — REST tests don't use the loader path
        SchemaInfo schema = new SchemaInfo();
        schema.addColumn("articles", "id", "bigint");
        schema.addColumn("articles", "title", "text");
        schema.addColumn("articles", "body", "text");
        schema.addPrimaryKey("articles", "id");
        schema.setTableSchema("articles", "rfts");

        SqlDialect dialect = new PostgresDialect();
        compiler = new RestQueryCompiler(schema, dialect, "rfts", 100);
    }

    @AfterAll
    static void tearDown() {
        if (ds != null) ds.close();
    }

    /**
     * Execute the compiled SQL and return the "articles" array from the jsonb
     * result envelope produced by RestQueryCompiler.
     */
    @SuppressWarnings("unchecked")
    private List<String> titlesOf(RestQueryCompiler.CompiledResult r) {
        MapSqlParameterSource ps = new MapSqlParameterSource();
        r.params().forEach(ps::addValue);
        Object result = named.queryForObject(r.sql(), ps, Object.class);
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Object parsed = mapper.readValue(result == null ? "[]" : result.toString(), Object.class);
            List<Map<String, Object>> rows;
            if (parsed instanceof List<?> list) {
                rows = (List<Map<String, Object>>) list;
            } else if (parsed instanceof Map<?, ?> map && map.get("articles") instanceof List<?> list) {
                rows = (List<Map<String, Object>>) list;
            } else {
                return List.of();
            }
            return rows.stream().map(r2 -> (String) r2.get("title")).toList();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private RestQueryCompiler.CompiledResult fts(String op, String value) {
        var filters = List.of(new RestQueryCompiler.FilterSpec("body", op, value, false));
        return compiler.compileSelect("articles", List.of("id", "title"), filters, null, 100, 0, false);
    }

    @Test
    @DisplayName("plfts executes via plainto_tsquery — matches a distinctive term")
    void plftsMatches() {
        // "controllers" only appears in the k8s article body — unique single match
        List<String> hits = titlesOf(fts("plfts", "controllers"));
        assertEquals(1, hits.size(), "only the k8s body mentions 'controllers'");
        assertEquals("Kubernetes operators", hits.get(0));
    }

    @Test
    @DisplayName("plfts stems words — 'indexes' matches 'indexing'")
    void plftsStemming() {
        List<String> hits = titlesOf(fts("plfts", "indexes"));
        assertFalse(hits.isEmpty(), "english stemmer should match 'indexing' to 'indexes'");
        // Two seeded rows carry 'indexing' / 'indexes' in the body
        assertTrue(hits.stream().anyMatch(s -> s.equals("Postgres tips") || s.equals("Database performance")));
    }

    @Test
    @DisplayName("plfts returns empty set for an unmatched term")
    void plftsNoMatch() {
        List<String> hits = titlesOf(fts("plfts", "xyznomatch"));
        assertTrue(hits.isEmpty());
    }

    @Test
    @DisplayName("wfts: web-style syntax — 'k8s OR operator'")
    void wftsWebSearch() {
        // websearch_to_tsquery accepts OR / AND / - syntax. "operator" is in the k8s body.
        List<String> hits = titlesOf(fts("wfts", "operator"));
        assertFalse(hits.isEmpty());
        assertTrue(hits.contains("Kubernetes operators"));
    }

    @Test
    @DisplayName("phfts: phrase search — adjacent words match")
    void phftsPhrase() {
        // phraseto_tsquery('Indexing strategies') matches only where those
        // tokens appear in order and adjacent.
        List<String> hits = titlesOf(fts("phfts", "indexing strategies"));
        assertEquals(1, hits.size());
        assertEquals("Postgres tips", hits.get(0));
    }

    @Test
    @DisplayName("FTS combines with other filters (AND)")
    void ftsCombinesWithEqFilter() {
        var filters = List.of(
                new RestQueryCompiler.FilterSpec("body", "plfts", "indexes", false),
                new RestQueryCompiler.FilterSpec("title", "eq", "Postgres tips", false)
        );
        var r = compiler.compileSelect("articles", List.of("id", "title"), filters, null, 100, 0, false);
        List<String> hits = titlesOf(r);
        assertEquals(1, hits.size());
        assertEquals("Postgres tips", hits.get(0));
    }

    @Test
    @DisplayName("FTS bind param is safe against injection attempts")
    void ftsBindsParamAgainstInjection() {
        // If the value were string-interpolated, this would blow up or leak. With
        // proper parameter binding plainto_tsquery just tokenizes the input.
        List<String> hits = titlesOf(fts("plfts", "'; DROP TABLE rfts.articles;--"));
        // Doesn't matter what comes back — the table must still exist.
        assertNotNull(hits);
        Integer count = jdbc.queryForObject("SELECT count(*) FROM rfts.articles", Integer.class);
        assertEquals(5, count, "injection attempt must not have dropped the table");
    }
}
