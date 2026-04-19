package io.github.excalibase.nosql;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.nosql.compiler.DocumentQueryCompiler;
import io.github.excalibase.nosql.schema.CollectionSchemaManager;
import io.github.excalibase.nosql.service.DocumentExecutionService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end integration test for NoSQL vector similarity search using pgvector.
 * Requires the pgvector/pgvector:pg16 image — the vector type, cosine distance
 * operator ({@code <=>}), and HNSW index type all come from the extension.
 *
 * <p>Covers the full vector path:
 *   syncSchema with `vector: {field, dimensions}` → creates vector column + HNSW
 *   index → insert JSONB docs → set embedding column via UPDATE → compileVectorSearch
 *   produces SQL with cosine distance ordering → executeQuery returns nearest-first.
 */
@Testcontainers
class NoSqlVectorIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    static JdbcTemplate jdbc;
    static NamedParameterJdbcTemplate namedJdbc;
    static CollectionSchemaManager schemaManager;
    static DocumentExecutionService executionService;
    static DocumentQueryCompiler compiler;

    @BeforeAll
    static void setUp() {
        var ds = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        namedJdbc = new NamedParameterJdbcTemplate(ds);
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");

        schemaManager = new CollectionSchemaManager(jdbc, null);
        executionService = new DocumentExecutionService(namedJdbc, new ObjectMapper());

        schemaManager.syncSchema(Map.of("collections", Map.of(
                "docs", Map.of(
                        "indexes", List.of(),
                        "vector", Map.of("field", "embedding", "dimensions", 3)
                )
        )));
        compiler = new DocumentQueryCompiler(schemaManager.getCollectionInfo());

        insertDocWithEmbedding("origin", "[1,0,0]");
        insertDocWithEmbedding("near",   "[0.9,0.1,0]");
        insertDocWithEmbedding("other",  "[0,1,0]");
        insertDocWithEmbedding("far",    "[0,0,1]");
    }

    private static void insertDocWithEmbedding(String title, String embeddingLiteral) {
        var inserted = executionService.executeMutation(
                compiler.compileInsertOne("docs", Map.of("title", title)));
        String id = (String) inserted.get("id");
        jdbc.update("UPDATE nosql.docs SET embedding = ?::vector WHERE id = ?::uuid",
                embeddingLiteral, id);
    }

    @Test
    @DisplayName("compileSetEmbedding updates vector column via compiler path")
    void setEmbedding_updatesVectorColumn() {
        var inserted = executionService.executeMutation(
                compiler.compileInsertOne("docs", Map.of("title", "ingest-target")));
        String id = (String) inserted.get("id");

        executionService.executeMutation(
                compiler.compileSetEmbedding("docs", id, List.of(0.5, 0.5, 0.0)));

        var stored = jdbc.queryForObject(
                "SELECT embedding::text FROM nosql.docs WHERE id = ?::uuid",
                String.class, id);
        assertThat(stored).contains("0.5").contains("0");
    }

    @Test
    @DisplayName("compileSetEmbedding throws on collection without vector field")
    void setEmbedding_noVectorField_throws() {
        schemaManager.syncSchema(Map.of("collections", Map.of(
                "no_vec_ingest", Map.of("indexes", List.of())
        )));
        var freshCompiler = new DocumentQueryCompiler(schemaManager.getCollectionInfo());

        assertThatThrownBy(() -> freshCompiler.compileSetEmbedding("no_vec_ingest", "ignored", List.of(0.1, 0.2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no vector field");
    }

    @Test
    @DisplayName("compileSetEmbedding throws on empty embedding")
    void setEmbedding_emptyEmbedding_throws() {
        assertThatThrownBy(() -> compiler.compileSetEmbedding("docs", "any-id", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty");
    }

    @Test
    @DisplayName("syncSchema creates vector column and HNSW index")
    void syncSchema_createsVectorColumnAndHnswIndex() {
        Integer columnCount = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns " +
                "WHERE table_schema = 'nosql' AND table_name = 'docs' " +
                "AND column_name = 'embedding' AND udt_name = 'vector'",
                Integer.class);
        assertThat(columnCount).isEqualTo(1);

        Integer indexCount = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes " +
                "WHERE schemaname = 'nosql' AND tablename = 'docs' " +
                "AND indexdef LIKE '%USING hnsw%embedding%vector_cosine_ops%'",
                Integer.class);
        assertThat(indexCount).isEqualTo(1);
    }

    @Test
    @DisplayName("collection schema exposes vector definition after sync")
    void collection_exposesVectorDef() {
        var schema = schemaManager.getCollectionInfo().getCollection("docs");
        assertThat(schema).isPresent();
        assertThat(schema.get().vector()).isNotNull();
        assertThat(schema.get().vector().field()).isEqualTo("embedding");
    }

    @Test
    @DisplayName("vectorSearch orders results by cosine distance")
    void vectorSearch_returnsNearestByCosineDistance() {
        var compiled = compiler.compileVectorSearch("docs", List.of(1.0, 0.0, 0.0), 3);
        var results = executionService.executeQuery(compiled);

        assertThat(results).hasSize(3);
        assertThat(results.get(0).get("title")).isEqualTo("origin");
        assertThat(results.get(1).get("title")).isEqualTo("near");
    }

    @Test
    @DisplayName("vectorSearch respects topK limit")
    void vectorSearch_respectsTopK() {
        var compiled = compiler.compileVectorSearch("docs", List.of(1.0, 0.0, 0.0), 2);
        var results = executionService.executeQuery(compiled);
        assertThat(results).hasSize(2);
    }

    @Test
    @DisplayName("vectorSearch throws when collection has no vector field")
    void vectorSearch_collectionWithoutVectorField_throws() {
        schemaManager.syncSchema(Map.of("collections", Map.of(
                "no_vector", Map.of("indexes", List.of())
        )));
        var freshCompiler = new DocumentQueryCompiler(schemaManager.getCollectionInfo());

        assertThatThrownBy(() -> freshCompiler.compileVectorSearch("no_vector", List.of(0.1, 0.2, 0.3), 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no vector field");
    }

    @Test
    @DisplayName("vectorSearch throws when collection does not exist")
    void vectorSearch_unknownCollection_throws() {
        assertThatThrownBy(() -> compiler.compileVectorSearch("ghost", List.of(0.1, 0.2, 0.3), 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown collection");
    }
}
