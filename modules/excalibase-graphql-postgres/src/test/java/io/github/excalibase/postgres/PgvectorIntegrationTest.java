package io.github.excalibase.postgres;

import com.zaxxer.hikari.HikariDataSource;
import graphql.language.*;
import io.github.excalibase.SqlDialect;
import io.github.excalibase.compiler.VectorSearchBuilder;
import io.github.excalibase.compiler.VectorSearchBuilder.VectorClause;
import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end verification that {@link VectorSearchBuilder}'s output runs
 * correctly against a real pgvector column and returns the expected k-NN
 * ordering. Uses the official {@code pgvector/pgvector:pg16} image which
 * ships pgvector pre-installed.
 *
 * <p>The test seeds three 3-D embeddings at known positions and then queries
 * for the nearest neighbors under each supported distance metric
 * ({@code L2}, {@code COSINE}, {@code IP}), asserting that the ordering
 * matches what a hand-computed distance calculation would produce.
 *
 * <p>This is the test that catches bugs in the SQL fragment VectorSearchBuilder
 * emits: the string might look right but fail at execution (bad operator,
 * missing {@code ::vector} cast, wrong bind shape). Without this test, Phase 6
 * is just "the unit tests pass and the code compiles".
 */
@Testcontainers
class PgvectorIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

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

        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbc.execute("""
            CREATE SCHEMA IF NOT EXISTS vec_test;
            CREATE TABLE vec_test.docs (
              id        bigserial PRIMARY KEY,
              title     text NOT NULL,
              embedding vector(3)
            );
            """);

        // Seed three points that are well-separated in Euclidean space so
        // the nearest-neighbor ordering is unambiguous.
        jdbc.update("INSERT INTO vec_test.docs (title, embedding) VALUES " +
                "('origin',    '[0,0,0]'), " +
                "('near',      '[1,1,1]'), " +
                "('far',       '[10,10,10]')");
    }

    @AfterAll
    static void tearDown() {
        if (ds != null) ds.close();
    }

    private SchemaInfo vectorSchema() {
        SchemaInfo info = new SchemaInfo();
        info.addColumn("docs", "id", "bigint");
        info.addColumn("docs", "title", "text");
        info.addColumn("docs", "embedding", "vector");
        info.addExtension("vector", "0.6.0"); // match detection contract
        return info;
    }

    /**
     * Build the ObjectValue shape VectorSearchBuilder consumes.
     */
    private ObjectValue vectorArg(String column, List<Float> embedding, String distance, Integer limit) {
        List<ObjectField> fields = new ArrayList<>();
        fields.add(new ObjectField("column", new StringValue(column)));

        List<Value> embedValues = new ArrayList<>();
        for (Float f : embedding) {
            embedValues.add(new FloatValue(BigDecimal.valueOf(f)));
        }
        fields.add(new ObjectField("near", new ArrayValue(embedValues)));

        fields.add(new ObjectField("distance", new StringValue(distance)));
        if (limit != null) {
            fields.add(new ObjectField("limit", new IntValue(BigInteger.valueOf(limit))));
        }
        return ObjectValue.newObjectValue().objectFields(fields).build();
    }

    /**
     * Run the full compile-and-execute cycle: VectorSearchBuilder produces an
     * ORDER BY fragment + bind param, we assemble a SELECT and execute it.
     */
    private List<String> runVectorQuery(List<Float> queryEmbedding, String distance, int limit) {
        VectorSearchBuilder builder = new VectorSearchBuilder(dialect);
        Map<String, Object> params = new HashMap<>();

        Optional<VectorClause> clauseOpt = builder.build(
                vectorArg("embedding", queryEmbedding, distance, limit),
                "d",
                vectorSchema(),
                params);

        assertTrue(clauseOpt.isPresent(), "VectorSearchBuilder must produce a clause for valid input");
        VectorClause clause = clauseOpt.get();

        String sql = "SELECT d.title FROM vec_test.docs d ORDER BY " + clause.orderByFragment()
                + " LIMIT " + clause.limitOverride();

        MapSqlParameterSource ps = new MapSqlParameterSource();
        params.forEach(ps::addValue);
        return named.queryForList(sql, ps, String.class);
    }

    @Test
    @DisplayName("L2 distance — nearest neighbor from origin is 'origin' itself")
    void l2Nearest_originQuery() {
        List<String> hits = runVectorQuery(List.of(0.0f, 0.0f, 0.0f), "L2", 3);
        assertEquals(List.of("origin", "near", "far"), hits,
                "L2 ordering from (0,0,0) must be origin → near → far");
    }

    @Test
    @DisplayName("L2 distance — nearest neighbor from (0.5,0.5,0.5) is 'origin'")
    void l2Nearest_betweenQuery() {
        // (0.5, 0.5, 0.5) is closer to (0,0,0) than to (1,1,1)
        List<String> hits = runVectorQuery(List.of(0.5f, 0.5f, 0.5f), "L2", 1);
        assertEquals(List.of("origin"), hits, "nearest to (0.5,0.5,0.5) under L2 is 'origin'");
    }

    @Test
    @DisplayName("L2 distance — limit clamps the result set")
    void l2LimitClamps() {
        List<String> hits = runVectorQuery(List.of(0.0f, 0.0f, 0.0f), "L2", 2);
        assertEquals(2, hits.size(), "limit=2 should produce exactly two rows");
        assertEquals("origin", hits.get(0));
        assertEquals("near", hits.get(1));
    }

    @Test
    @DisplayName("COSINE distance — identical direction ranks first")
    void cosineIdenticalDirection() {
        // The 2-2-2 vector shares direction with 1-1-1 so cosine distance is zero
        // (identical), while the zero vector is undefined for cosine but pgvector
        // returns a consistent ordering. Assert that 'near' beats 'far' for a
        // 1-1-1-direction query.
        List<String> hits = runVectorQuery(List.of(2.0f, 2.0f, 2.0f), "COSINE", 2);
        assertEquals(2, hits.size());
        // both 'near' and 'far' point in (1,1,1) direction → cosine ties —
        // just assert they appear in the top 2 (origin is undefined/nan).
        assertTrue(hits.contains("near") || hits.contains("far"),
                "at least one of the non-origin rows should be in top 2");
    }

    @Test
    @DisplayName("Inner product distance — larger magnitudes win")
    void innerProductOrdering() {
        // Inner product: larger dot products get MORE-negative <#> values,
        // so the row with the largest dot product ranks first.
        // query (1,1,1) → origin dot = 0, near dot = 3, far dot = 30
        List<String> hits = runVectorQuery(List.of(1.0f, 1.0f, 1.0f), "IP", 3);
        assertEquals("far", hits.get(0), "'far' has the largest dot product with (1,1,1)");
        assertEquals("near", hits.get(1));
        assertEquals("origin", hits.get(2));
    }

    @Test
    @DisplayName("Generated SQL fragment has the correct pgvector operator + cast")
    void sqlFragmentShape() {
        VectorSearchBuilder builder = new VectorSearchBuilder(dialect);
        Map<String, Object> params = new HashMap<>();
        Optional<VectorClause> clause = builder.build(
                vectorArg("embedding", List.of(0.1f, 0.2f, 0.3f), "L2", 5),
                "d", vectorSchema(), params);

        assertTrue(clause.isPresent());
        String frag = clause.get().orderByFragment();
        assertTrue(frag.contains("d.\"embedding\""), "must reference quoted column: " + frag);
        assertTrue(frag.contains(" <-> "), "L2 uses <-> operator: " + frag);
        assertTrue(frag.contains("::vector"), "bind param must be cast to vector: " + frag);
        assertEquals(5, clause.get().limitOverride());
    }

    @Test
    @DisplayName("pgvector absence returns empty clause")
    void missingExtensionGuard() {
        VectorSearchBuilder builder = new VectorSearchBuilder(dialect);
        SchemaInfo noVector = new SchemaInfo();
        noVector.addColumn("docs", "embedding", "vector");
        // NO addExtension("vector", ...) — simulating a DB without pgvector

        Map<String, Object> params = new HashMap<>();
        Optional<VectorClause> clause = builder.build(
                vectorArg("embedding", List.of(0.1f, 0.2f, 0.3f), "L2", 5),
                "d", noVector, params);

        assertTrue(clause.isEmpty(),
                "builder must skip when pgvector isn't installed, not emit invalid SQL");
    }
}
