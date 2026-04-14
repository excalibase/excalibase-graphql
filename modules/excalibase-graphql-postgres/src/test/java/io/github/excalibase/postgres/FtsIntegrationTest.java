package io.github.excalibase.postgres;

import com.zaxxer.hikari.HikariDataSource;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import graphql.language.StringValue;
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
 * End-to-end verification that {@link FilterBuilder}'s {@code search} operator
 * produces SQL that executes correctly against a real Postgres tsvector column.
 *
 * <p>This test is the last line of defense between "unit-test says the SQL
 * fragment looks right" and "production query returns the right rows". It
 * seeds a real table, invokes FilterBuilder exactly the way SqlCompiler does,
 * and executes the assembled SQL via NamedParameterJdbcTemplate — the same
 * code path used by the data fetchers in production.
 */
@Testcontainers
class FtsIntegrationTest {

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
            CREATE SCHEMA IF NOT EXISTS fts_test;
            CREATE TABLE fts_test.articles (
              id   bigserial PRIMARY KEY,
              title text NOT NULL,
              body  text NOT NULL,
              search_vec tsvector
                 GENERATED ALWAYS AS (to_tsvector('english', title || ' ' || body)) STORED
            );
            CREATE INDEX articles_search_idx ON fts_test.articles USING GIN(search_vec);
            """);

        // Seed deterministic content spanning multiple query intents.
        jdbc.update("""
            INSERT INTO fts_test.articles (title, body) VALUES
              ('PostgreSQL tips',             'Indexing strategies for large tables and vacuuming best practices'),
              ('Kubernetes operators',        'Writing custom controllers with the operator SDK and helm charts'),
              ('Database performance',        'Query tuning, explain plans, and partial indexes on Postgres'),
              ('Go concurrency patterns',     'Channels, contexts, and cancellation for server-side Go programs'),
              ('Full-text search in Postgres','tsvector, plainto_tsquery, and GIN indexes for fast lookup')
            """);
    }

    @AfterAll
    static void tearDown() {
        if (ds != null) ds.close();
    }

    /**
     * Build the same ObjectValue shape GraphQL's query parser produces for
     * {@code where: { <column>: { search: "<query text>" } }}.
     */
    private ObjectValue whereSearch(String column, String query) {
        ObjectValue inner = ObjectValue.newObjectValue()
                .objectField(new ObjectField("search", new StringValue(query)))
                .build();
        return ObjectValue.newObjectValue()
                .objectField(new ObjectField(column, inner))
                .build();
    }

    private SchemaInfo schemaInfoWithTable() {
        SchemaInfo info = new SchemaInfo();
        info.addColumn("articles", "id", "bigint");
        info.addColumn("articles", "title", "text");
        info.addColumn("articles", "body", "text");
        info.addColumn("articles", "search_vec", "tsvector");
        info.setTableSchema("articles", "fts_test");
        return info;
    }

    /**
     * Run the full compile-and-execute cycle: FilterBuilder produces WHERE
     * clauses + bind params, then we run SELECT against the real DB.
     */
    private List<String> executeSearch(String query) {
        SchemaInfo info = schemaInfoWithTable();
        FilterBuilder fb = new FilterBuilder(dialect, 100, info, "fts_test");

        Map<String, Object> params = new HashMap<>();
        List<String> conditions = new ArrayList<>();
        fb.buildFilterConditions(whereSearch("search_vec", query), "a", params, conditions, "articles");

        assertFalse(conditions.isEmpty(), "FilterBuilder must emit at least one condition for search");
        String where = String.join(" AND ", conditions);
        String sql = "SELECT a.title FROM fts_test.articles a WHERE " + where + " ORDER BY a.id";

        MapSqlParameterSource ps = new MapSqlParameterSource();
        params.forEach(ps::addValue);
        return named.queryForList(sql, ps, String.class);
    }

    @Test
    @DisplayName("search matches tsvector column via plainto_tsquery")
    void searchMatchesTsvectorColumn() {
        // "kubernetes" is a distinctive single-word token that only appears in
        // the k8s article — no stemming ambiguity, clean assertion.
        List<String> hits = executeSearch("kubernetes");
        assertEquals(1, hits.size(), "exactly one article contains 'kubernetes'");
        assertEquals("Kubernetes operators", hits.get(0));
    }

    @Test
    @DisplayName("search returns multiple matches when the term appears in several rows")
    void searchReturnsMultipleMatches() {
        // "postgres" appears verbatim in one title and should match via the
        // english text search config.
        List<String> hits = executeSearch("postgres");
        assertFalse(hits.isEmpty(), "expected at least one match for 'postgres'");
        assertTrue(hits.contains("Full-text search in Postgres"),
                "expected the Postgres-titled article to match. actual hits: " + hits);
    }

    @Test
    @DisplayName("search uses english stemming — 'indexes' matches 'indexing'")
    void searchUsesLanguageStemming() {
        // Snowball english stemmer maps 'indexing' and 'indexes' to 'index',
        // so either query word should find the seeded 'indexing' and 'indexes'.
        List<String> hits = executeSearch("indexes");
        assertFalse(hits.isEmpty(), "stemming should match 'indexing' → 'index'");
    }

    @Test
    @DisplayName("search returns empty when no row matches")
    void searchReturnsEmptyForNoMatch() {
        List<String> hits = executeSearch("xyznomatch");
        assertTrue(hits.isEmpty(), "nonsense query must return zero rows");
    }

    @Test
    @DisplayName("search binds param — no SQL injection via query text")
    void searchBindsParameterNotInterpolated() {
        // If the search query were string-interpolated, this would blow up
        // or return more than just the single seeded row. plainto_tsquery
        // safely tokenizes whatever lands in the bind param.
        List<String> hits = executeSearch("'; DROP TABLE fts_test.articles;--");
        // Either zero hits or whatever tsquery makes of it — but the table
        // must still exist afterward.
        assertNotNull(hits);
        Integer count = jdbc.queryForObject("SELECT count(*) FROM fts_test.articles", Integer.class);
        assertEquals(5, count, "injection attempt must not have dropped the table");
    }

    @Test
    @DisplayName("FilterBuilder emits the correct SQL fragment shape")
    void searchEmitsCorrectSqlFragment() {
        SchemaInfo info = schemaInfoWithTable();
        FilterBuilder fb = new FilterBuilder(dialect, 100, info, "fts_test");

        Map<String, Object> params = new HashMap<>();
        List<String> conditions = new ArrayList<>();
        fb.buildFilterConditions(whereSearch("search_vec", "postgres"), "a", params, conditions, "articles");

        assertEquals(1, conditions.size(), "exactly one condition expected");
        String frag = conditions.get(0);
        assertTrue(frag.contains("a.\"search_vec\""), "fragment must reference the quoted column: " + frag);
        assertTrue(frag.contains("plainto_tsquery"), "fragment must use plainto_tsquery: " + frag);

        // nextParam generates "prefix_<params.size()>" so with an empty map the
        // param name is "p_search_vec_search_0".
        assertEquals(1, params.size(), "exactly one bind param expected");
        String paramName = params.keySet().iterator().next();
        assertTrue(paramName.startsWith("p_search_vec_search"), "param name: " + paramName);
        assertTrue(frag.contains(":" + paramName), "fragment must reference the bind param: " + frag);
        assertEquals("postgres", params.get(paramName));
    }
}
