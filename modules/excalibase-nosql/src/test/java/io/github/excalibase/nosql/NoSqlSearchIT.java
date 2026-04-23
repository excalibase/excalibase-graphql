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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end integration test for NoSQL full-text search using the core PostgreSQL
 * FTS engine (tsvector/websearch_to_tsquery). No extensions required — standard
 * postgres:16-alpine image is sufficient.
 *
 * <p>Covers the complete FTS path:
 *   syncSchema with `search: fieldname` → generated tsvector column + GIN index →
 *   insert JSONB documents → compileSearch produces SQL with ts_rank ordering →
 *   executeQuery runs against real Postgres → results ranked by relevance.
 */
@Testcontainers
class NoSqlSearchIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

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
        schemaManager = new CollectionSchemaManager(jdbc,
                new io.github.excalibase.nosql.schema.JsonSchemaValidator(new ObjectMapper()), null);
        executionService = new DocumentExecutionService(namedJdbc, new ObjectMapper());

        schemaManager.syncSchema(Map.of("collections", Map.of(
                "articles", Map.of(
                        "indexes", List.of(),
                        "search", "body"
                )
        )));
        compiler = new DocumentQueryCompiler(schemaManager.getCollectionInfo());

        executionService.executeMutation(compiler.compileInsertOne("articles",
                Map.of("title", "postgres fts", "body", "PostgreSQL tsvector and tsquery power full-text search")));
        executionService.executeMutation(compiler.compileInsertOne("articles",
                Map.of("title", "mysql", "body", "MySQL has its own full-text search implementation")));
        executionService.executeMutation(compiler.compileInsertOne("articles",
                Map.of("title", "general", "body", "Search engines index documents for fast retrieval")));
        executionService.executeMutation(compiler.compileInsertOne("articles",
                Map.of("title", "postgres only", "body", "PostgreSQL is a powerful relational database")));
        executionService.executeMutation(compiler.compileInsertOne("articles",
                Map.of("title", "unrelated", "body", "Cooking recipes for pasta")));
    }

    @Test
    @DisplayName("syncSchema creates tsvector column and GIN index")
    void syncSchema_createsTsvectorColumnAndGinIndex() {
        Integer columnCount = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns " +
                "WHERE table_schema = 'nosql' AND table_name = 'articles' " +
                "AND column_name = 'search_text' AND udt_name = 'tsvector'",
                Integer.class);
        assertThat(columnCount).isEqualTo(1);

        Integer indexCount = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes " +
                "WHERE schemaname = 'nosql' AND tablename = 'articles' " +
                "AND indexdef LIKE '%USING gin%search_text%'",
                Integer.class);
        assertThat(indexCount).isEqualTo(1);
    }

    @Test
    @DisplayName("collection schema exposes search field after sync")
    void collection_exposesSearchField() {
        var schema = schemaManager.getCollectionInfo().getCollection("articles");
        assertThat(schema).isPresent();
        assertThat(schema.get().searchField()).isEqualTo("search_text");
    }

    @Test
    @DisplayName("search ranks docs containing query terms higher")
    void search_rankedByRelevance() {
        var compiled = compiler.compileSearch("articles", "tsvector tsquery", 10);
        var results = executionService.executeQuery(compiled);

        assertThat(results).isNotEmpty();
        assertThat(results.getFirst()).containsEntry("title", "postgres fts");
    }

    @Test
    @DisplayName("search with no matches returns empty list")
    void search_noMatches_returnsEmpty() {
        var compiled = compiler.compileSearch("articles", "nonexistentwordxyz", 10);
        var results = executionService.executeQuery(compiled);
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("search respects limit")
    void search_respectsLimit() {
        var compiled = compiler.compileSearch("articles", "search OR database OR recipes", 2);
        var results = executionService.executeQuery(compiled);
        assertThat(results).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("search throws when collection has no search field")
    void search_collectionWithoutSearchField_throws() {
        schemaManager.syncSchema(Map.of("collections", Map.of(
                "no_search", Map.of("indexes", List.of())
        )));
        var freshCompiler = new DocumentQueryCompiler(schemaManager.getCollectionInfo());

        assertThatThrownBy(() -> freshCompiler.compileSearch("no_search", "anything", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no search field");
    }

    @Test
    @DisplayName("search throws when collection does not exist")
    void search_unknownCollection_throws() {
        assertThatThrownBy(() -> compiler.compileSearch("ghost", "term", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown collection");
    }
}
