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
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end verification of the REST {@code vector.{json}} operator.
 *
 * <p>URL shape: {@code ?embedding=vector.{"near":[0.1,0.2,0.3],"distance":"L2","limit":10}}.
 * The compiler extracts the vector filter before buildWhere, delegates to
 * {@link io.github.excalibase.compiler.VectorSearchBuilder#buildFromMap} for
 * SQL generation, and composes the result as ORDER BY + LIMIT override. This
 * test seeds a real pgvector column, drives the compile path with a FilterSpec,
 * and asserts the resulting k-NN ordering — the equivalent of
 * {@code GraphQLVectorRuntimeTest} on the REST side.
 */
@Testcontainers
class RestVectorIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

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

        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbc.execute("""
            CREATE SCHEMA IF NOT EXISTS rvec;
            CREATE TABLE rvec.docs (
              id        bigserial PRIMARY KEY,
              title     text NOT NULL,
              embedding vector(3)
            );
            """);
        jdbc.update("INSERT INTO rvec.docs (title, embedding) VALUES " +
                "('origin', '[0,0,0]'), " +
                "('near',   '[1,1,1]'), " +
                "('mid',    '[3,3,3]'), " +
                "('far',    '[10,10,10]')");

        SchemaInfo schema = new SchemaInfo();
        schema.addColumn("docs", "id", "bigint");
        schema.addColumn("docs", "title", "text");
        schema.addColumn("docs", "embedding", "vector");
        schema.addPrimaryKey("docs", "id");
        schema.setTableSchema("docs", "rvec");
        // pgvector extension must be recorded so VectorSearchBuilder doesn't
        // short-circuit on the absence guard.
        schema.addExtension("vector", "0.6.0");

        SqlDialect dialect = new PostgresDialect();
        compiler = new RestQueryCompiler(schema, dialect, "rvec", 100);
    }

    @AfterAll
    static void tearDown() { if (ds != null) ds.close(); }

    /**
     * Execute a REST compile + query cycle with a single vector FilterSpec,
     * returning titles in ORDER BY order.
     */
    @SuppressWarnings("unchecked")
    private List<String> runVector(String near, String distance, Integer limit) {
        StringBuilder json = new StringBuilder("{\"near\":").append(near)
                .append(",\"distance\":\"").append(distance).append("\"");
        if (limit != null) json.append(",\"limit\":").append(limit);
        json.append("}");

        var filters = List.of(new RestQueryCompiler.FilterSpec("embedding", "vector", json.toString(), false));
        var r = compiler.compileSelect("docs", List.of("id", "title"), filters, null, 100, 0, false);

        MapSqlParameterSource ps = new MapSqlParameterSource();
        r.params().forEach(ps::addValue);
        Object result = named.queryForObject(r.sql(), ps, Object.class);
        try {
            var mapper = new ObjectMapper();
            List<Map<String, Object>> rows = mapper.readValue(
                    result == null ? "[]" : result.toString(), List.class);
            return rows.stream().map(row -> (String) row.get("title")).toList();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("REST vector.L2 from origin returns origin -> near -> mid -> far")
    void l2FromOrigin() {
        List<String> hits = runVector("[0.0,0.0,0.0]", "L2", 4);
        assertEquals(List.of("origin", "near", "mid", "far"), hits);
    }

    @Test
    @DisplayName("REST vector limit clamps the result set")
    void limitClamps() {
        List<String> hits = runVector("[0.0,0.0,0.0]", "L2", 2);
        assertEquals(2, hits.size());
        assertEquals("origin", hits.get(0));
        assertEquals("near", hits.get(1));
    }

    @Test
    @DisplayName("REST vector COSINE distance")
    void cosineDistance() {
        List<String> hits = runVector("[2.0,2.0,2.0]", "COSINE", 3);
        assertEquals(3, hits.size());
        // rows aligned with (1,1,1) direction cluster at top
        assertTrue(hits.contains("near") || hits.contains("mid") || hits.contains("far"));
    }

    @Test
    @DisplayName("REST vector inner product — largest dot product wins")
    void innerProductDistance() {
        List<String> hits = runVector("[1.0,1.0,1.0]", "IP", 4);
        // query (1,1,1): origin dot = 0, near = 3, mid = 9, far = 30
        assertEquals("far", hits.get(0));
        assertEquals("mid", hits.get(1));
        assertEquals("near", hits.get(2));
        assertEquals("origin", hits.get(3));
    }

    @Test
    @DisplayName("REST vector overrides user orderBy")
    void vectorOverridesOrderBy() {
        var filters = List.of(new RestQueryCompiler.FilterSpec("embedding", "vector",
                "{\"near\":[0.0,0.0,0.0],\"distance\":\"L2\",\"limit\":4}", false));
        var orderBy = List.of(new RestQueryCompiler.OrderBySpec("id", "desc", null));
        var r = compiler.compileSelect("docs", List.of("id", "title"), filters, orderBy, 100, 0, false);

        MapSqlParameterSource ps = new MapSqlParameterSource();
        r.params().forEach(ps::addValue);
        Object result = named.queryForObject(r.sql(), ps, Object.class);
        try {
            var mapper = new ObjectMapper();
            List<Map<String, Object>> rows = mapper.readValue(result.toString(), List.class);
            List<String> titles = rows.stream().map(row -> (String) row.get("title")).toList();
            // k-NN order wins over id DESC (which would give far, mid, near, origin)
            assertEquals(List.of("origin", "near", "mid", "far"), titles);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("REST vector combines with an eq filter — WHERE still applies")
    void vectorWithEqFilter() {
        // Filter to rows where title != 'far', then rank the rest by L2.
        var filters = List.of(
                new RestQueryCompiler.FilterSpec("embedding", "vector",
                        "{\"near\":[0.0,0.0,0.0],\"distance\":\"L2\",\"limit\":10}", false),
                new RestQueryCompiler.FilterSpec("title", "neq", "far", false)
        );
        var r = compiler.compileSelect("docs", List.of("id", "title"), filters, null, 100, 0, false);

        MapSqlParameterSource ps = new MapSqlParameterSource();
        r.params().forEach(ps::addValue);
        Object result = named.queryForObject(r.sql(), ps, Object.class);
        try {
            var mapper = new ObjectMapper();
            List<Map<String, Object>> rows = mapper.readValue(result.toString(), List.class);
            List<String> titles = rows.stream().map(row -> (String) row.get("title")).toList();
            assertEquals(List.of("origin", "near", "mid"), titles);
            assertFalse(titles.contains("far"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("REST vector malformed JSON returns empty (dropped silently)")
    void malformedJson() {
        var filters = List.of(new RestQueryCompiler.FilterSpec("embedding", "vector", "{not valid json", false));
        var r = compiler.compileSelect("docs", List.of("id", "title"), filters, null, 100, 0, false);

        // SQL must still be executable — the bad filter is silently dropped.
        MapSqlParameterSource ps = new MapSqlParameterSource();
        r.params().forEach(ps::addValue);
        Object result = named.queryForObject(r.sql(), ps, Object.class);
        assertNotNull(result, "query must still run even when vector filter is malformed");
    }
}
