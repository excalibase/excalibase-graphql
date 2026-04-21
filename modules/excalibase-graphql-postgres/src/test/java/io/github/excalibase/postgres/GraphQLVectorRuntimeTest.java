package io.github.excalibase.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.github.excalibase.SqlDialect;
import io.github.excalibase.compiler.SqlCompiler;
import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runtime integration test — proves that a real GraphQL query containing a
 * {@code vector: {...}} argument actually flows through
 * {@link SqlCompiler#compile(String, Map)} -> QueryBuilder.compileList ->
 * VectorSearchBuilder -> PostgresDialect -> NamedParameterJdbcTemplate and
 * returns the correct nearest-neighbor ordering.
 *
 * <p>This is the test that catches regressions in the integration wiring
 * between QueryBuilder and VectorSearchBuilder. {@code PgvectorIntegrationTest}
 * exercises the builder in isolation; THIS test sends a literal GraphQL query
 * string and asserts end-to-end correctness.
 */
@Testcontainers
class GraphQLVectorRuntimeTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    private static JdbcTemplate jdbc;
    private static NamedParameterJdbcTemplate named;
    private static HikariDataSource ds;
    private static SchemaInfo schemaInfo;
    private static SqlCompiler compiler;

    @BeforeAll
    static void setup() {
        ds = new HikariDataSource();
        ds.setJdbcUrl(postgres.getJdbcUrl());
        ds.setUsername(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        named = new NamedParameterJdbcTemplate(ds);

        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbc.execute("""
            CREATE SCHEMA IF NOT EXISTS vec_rt;
            CREATE TABLE vec_rt.docs (
              id        bigserial PRIMARY KEY,
              title     text NOT NULL,
              embedding vector(3)
            );
            """);
        jdbc.update("INSERT INTO vec_rt.docs (title, embedding) VALUES " +
                "('origin', '[0,0,0]'), " +
                "('near',   '[1,1,1]'), " +
                "('mid',    '[3,3,3]'), " +
                "('far',    '[10,10,10]')");

        // Load the schema via the real loader so extensions get detected the
        // same way as in production.
        PostgresSchemaLoader loader = new PostgresSchemaLoader();
        var perSchema = new LinkedHashMap<String, SchemaInfo>();
        loader.loadAll(jdbc, List.of("vec_rt"), perSchema);
        schemaInfo = perSchema.get("vec_rt");
        assertNotNull(schemaInfo, "vec_rt schema must load");
        assertTrue(schemaInfo.hasExtension("vector"), "pgvector must be detected");

        SqlDialect dialect = new PostgresDialect();
        compiler = new SqlCompiler(schemaInfo, "vec_rt", 100, dialect, new PostgresMutationCompiler());
    }

    @AfterAll
    static void tearDown() { if (ds != null) ds.close(); }

    @SuppressWarnings("unchecked")
    private List<String> titlesOf(SqlCompiler.CompiledQuery q) {
        MapSqlParameterSource ps = new MapSqlParameterSource();
        q.params().forEach(ps::addValue);
        Object result = named.queryForObject(q.sql(), ps, Object.class);
        // SqlCompiler wraps list queries as jsonb_build_object('<fieldName>', [...]).
        // Parse as Map, then extract the "docs" array, then extract titles.
        try {
            String json = result == null ? "{}" : result.toString();
            var mapper = new ObjectMapper();
            Map<String, Object> root = mapper.readValue(json, Map.class);
            List<Object> rows = (List<Object>) root.get("docs");
            if (rows == null) return List.of();
            return rows.stream()
                    .map(r -> (String) ((Map<String, Object>) r).get("title"))
                    .toList();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── Query shape helpers ──────────────────────────────────────────────────

    private String vectorQuery(String distance, String nearJson, int limit) {
        return """
            { docs(vector: {
                column: "embedding",
                near: %s,
                distance: "%s",
                limit: %d
              }) { id title } }
            """.formatted(nearJson, distance, limit);
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GraphQL vector runs through SqlCompiler and returns k-NN ordering")
    void graphqlVectorL2FromOrigin() {
        var query = compiler.compile(vectorQuery("L2", "[0.0, 0.0, 0.0]", 4), Map.of());
        List<String> titles = titlesOf(query);
        assertEquals(List.of("origin", "near", "mid", "far"), titles,
                "L2 from (0,0,0) must rank: origin, near, mid, far");
    }

    @Test
    @DisplayName("GraphQL vector respects the limit field, overriding default maxRows")
    void graphqlVectorLimit() {
        var query = compiler.compile(vectorQuery("L2", "[0.0, 0.0, 0.0]", 2), Map.of());
        List<String> titles = titlesOf(query);
        assertEquals(2, titles.size(), "limit=2 must clamp the result set");
        assertEquals("origin", titles.get(0));
        assertEquals("near", titles.get(1));
    }

    @Test
    @DisplayName("GraphQL vector with cosine distance")
    void graphqlVectorCosine() {
        // (1,1,1) direction — near and mid and far all point the same way,
        // so cosine distance is near-zero for all three. origin is undefined.
        var query = compiler.compile(vectorQuery("COSINE", "[1.0, 1.0, 1.0]", 3), Map.of());
        List<String> titles = titlesOf(query);
        assertEquals(3, titles.size());
        // All three unit-direction rows should be in the top 3
        assertTrue(titles.contains("near"));
        assertTrue(titles.contains("mid"));
        assertTrue(titles.contains("far"));
    }

    @Test
    @DisplayName("GraphQL vector with inner product distance")
    void graphqlVectorInnerProduct() {
        // query (1,1,1) → origin dot = 0, near dot = 3, mid dot = 9, far dot = 30
        // Inner product distance (<#>) is NEGATIVE dot product, so largest dot
        // product ranks first.
        var query = compiler.compile(vectorQuery("IP", "[1.0, 1.0, 1.0]", 4), Map.of());
        List<String> titles = titlesOf(query);
        assertEquals("far", titles.get(0), "'far' has the largest dot product");
        assertEquals("mid", titles.get(1));
        assertEquals("near", titles.get(2));
        assertEquals("origin", titles.get(3));
    }

    @Test
    @DisplayName("vector overrides user-supplied orderBy")
    void graphqlVectorOverridesOrderBy() {
        // Send both vector (k-NN) and orderBy (id DESC). vector must win.
        String query = """
            { docs(
                vector: { column: "embedding", near: [0.0, 0.0, 0.0], distance: "L2", limit: 4 },
                orderBy: { id: DESC }
              ) { id title } }
            """;
        List<String> titles = titlesOf(compiler.compile(query, Map.of()));
        // k-NN order: origin, near, mid, far — not reverse-id order (far, mid, near, origin)
        assertEquals(List.of("origin", "near", "mid", "far"), titles);
    }
}
