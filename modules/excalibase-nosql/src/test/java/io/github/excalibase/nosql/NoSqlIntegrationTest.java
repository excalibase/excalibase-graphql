package io.github.excalibase.nosql;

import io.github.excalibase.nosql.compiler.DocumentQueryCompiler;
import io.github.excalibase.nosql.compiler.FindOptions;
import io.github.excalibase.nosql.model.*;
import io.github.excalibase.nosql.schema.CollectionSchemaManager;
import io.github.excalibase.nosql.service.DocumentExecutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NoSqlIntegrationTest {

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
        schemaManager = new CollectionSchemaManager(jdbc, null);
        executionService = new DocumentExecutionService(namedJdbc, new ObjectMapper());
    }

    @Nested
    @Order(1)
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    @DisplayName("Schema sync")
    class SchemaSyncTests {

        @Test
        @Order(1)
        @DisplayName("syncSchema creates table and indexes")
        void syncSchema_createsTableAndIndexes() {
            var schema = Map.<String, Object>of("collections", Map.of(
                    "users", Map.of(
                            "indexes", List.of(
                                    Map.of("fields", List.of("email"), "type", "string", "unique", true),
                                    Map.of("fields", List.of("status"), "type", "string", "unique", false)
                            )
                    )
            ));

            var result = schemaManager.syncSchema(schema);

            assertThat(result).containsEntry("created", 1);
            assertThat(schemaManager.getCollectionInfo().hasCollection("users")).isTrue();

            var indexes = schemaManager.getCollectionInfo().getCollection("users").get().indexes();
            assertThat(indexes).hasSize(2);
            assertThat(schemaManager.getCollectionInfo().getCollection("users").get().indexedFields())
                    .containsExactlyInAnyOrder("email", "status");
        }

        @Test
        @Order(2)
        @DisplayName("syncSchema is idempotent")
        void syncSchema_idempotent() {
            var schema = Map.<String, Object>of("collections", Map.of(
                    "users", Map.of(
                            "indexes", List.of(
                                    Map.of("fields", List.of("email"), "type", "string", "unique", true),
                                    Map.of("fields", List.of("status"), "type", "string", "unique", false)
                            )
                    )
            ));

            var result = schemaManager.syncSchema(schema);
            assertThat(result).containsEntry("updated", 1);
            assertThat(result).containsEntry("created", 0);
        }

        @Test
        @Order(3)
        @DisplayName("syncSchema adds new index on re-sync")
        void syncSchema_addsNewIndex() {
            var schema = Map.<String, Object>of("collections", Map.of(
                    "users", Map.of(
                            "indexes", List.of(
                                    Map.of("fields", List.of("email"), "type", "string", "unique", true),
                                    Map.of("fields", List.of("status"), "type", "string", "unique", false),
                                    Map.of("fields", List.of("age"), "type", "number", "unique", false)
                            )
                    )
            ));

            schemaManager.syncSchema(schema);

            var indexes = schemaManager.getCollectionInfo().getCollection("users").get().indexes();
            assertThat(indexes).hasSize(3);
            assertThat(schemaManager.getCollectionInfo().getCollection("users").get().indexedFields())
                    .contains("age");
        }

        @Test
        @Order(4)
        @DisplayName("syncSchema drops removed index")
        void syncSchema_dropsRemovedIndex() {
            var schema = Map.<String, Object>of("collections", Map.of(
                    "users", Map.of(
                            "indexes", List.of(
                                    Map.of("fields", List.of("email"), "type", "string", "unique", true)
                            )
                    )
            ));

            schemaManager.syncSchema(schema);

            var info = schemaManager.getCollectionInfo().getCollection("users").get();
            assertThat(info.indexes()).hasSize(1);
            assertThat(info.indexedFields()).containsExactly("email");
        }

        @Test
        @Order(5)
        @DisplayName("compound index on multiple fields")
        void syncSchema_compoundIndex() {
            var schema = Map.<String, Object>of("collections", Map.of(
                    "events", Map.of(
                            "indexes", List.of(
                                    Map.of("fields", List.of("userId", "status"), "type", "string", "unique", false)
                            )
                    )
            ));

            schemaManager.syncSchema(schema);

            var info = schemaManager.getCollectionInfo().getCollection("events").get();
            assertThat(info.indexes()).hasSize(1);
            assertThat(info.indexes().getFirst().fields()).containsExactly("userId", "status");
            assertThat(info.indexedFields()).containsExactlyInAnyOrder("userId", "status");
        }

        @Test
        @Order(6)
        @DisplayName("syncSchema rejects > 10 indexes")
        void syncSchema_rejectsTooManyIndexes() {
            var indexes = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < 11; i++) {
                indexes.add(Map.of("fields", List.of("field" + i), "type", "string", "unique", false));
            }

            assertThatThrownBy(() -> schemaManager.syncSchema(
                    Map.of("collections", Map.of("big", Map.of("indexes", indexes)))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("max is 10");
        }
    }

    @Nested
    @Order(2)
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    @DisplayName("CRUD operations")
    class CrudTests {

        @BeforeAll
        static void setupCollection() {
            schemaManager.syncSchema(Map.of("collections", Map.of(
                    "posts", Map.of(
                            "indexes", List.of(
                                    Map.of("fields", List.of("title"), "type", "string", "unique", false),
                                    Map.of("fields", List.of("status"), "type", "string", "unique", false)
                            )
                    )
            )));
            compiler = new DocumentQueryCompiler(schemaManager.getCollectionInfo());
        }

        @Test
        @Order(1)
        @DisplayName("insertOne and find roundtrip")
        void insertOneAndFind() {
            var doc = Map.<String, Object>of("title", "Hello", "status", "draft", "body", "World");
            var insertCompiled = compiler.compileInsertOne("posts", doc);
            var inserted = executionService.executeMutation(insertCompiled);

            assertThat(inserted).containsKey("id");
            assertThat(inserted).containsEntry("title", "Hello");
            assertThat(inserted).containsEntry("status", "draft");
            assertThat(inserted).containsKey("createdAt");

            var findCompiled = compiler.compileFind("posts",
                    Map.of("title", "Hello"), new FindOptions(30, 0, null));
            var found = executionService.executeQuery(findCompiled);

            assertThat(found).hasSize(1);
            assertThat(found.getFirst()).containsEntry("title", "Hello");
            assertThat(found.getFirst().get("id")).isEqualTo(inserted.get("id"));
        }

        @Test
        @Order(2)
        @DisplayName("getById returns inserted document")
        void getById() {
            var doc = Map.<String, Object>of("title", "ById Test", "status", "published");
            var inserted = executionService.executeMutation(compiler.compileInsertOne("posts", doc));
            String id = (String) inserted.get("id");

            var found = executionService.executeSingleQuery(compiler.compileGetById("posts", id));
            assertThat(found).isNotNull();
            assertThat(found).containsEntry("title", "ById Test");
        }

        @Test
        @Order(3)
        @DisplayName("updateOne with $set")
        void updateOne() {
            var doc = Map.<String, Object>of("title", "Update Me", "status", "draft");
            executionService.executeMutation(compiler.compileInsertOne("posts", doc));

            var updated = executionService.executeMutation(
                    compiler.compileUpdateOne("posts",
                            Map.of("title", "Update Me"),
                            Map.of("$set", Map.of("status", "published"))));

            assertThat(updated).containsEntry("status", "published");
            assertThat(updated).containsEntry("title", "Update Me");
        }

        @Test
        @Order(4)
        @DisplayName("deleteOne removes document")
        void deleteOne() {
            var doc = Map.<String, Object>of("title", "Delete Me", "status", "trash");
            executionService.executeMutation(compiler.compileInsertOne("posts", doc));

            var deleted = executionService.executeMutation(
                    compiler.compileDeleteOne("posts", Map.of("title", "Delete Me")));
            assertThat(deleted).containsEntry("title", "Delete Me");

            var found = executionService.executeQuery(
                    compiler.compileFind("posts", Map.of("title", "Delete Me"), new FindOptions(30, 0, null)));
            assertThat(found).isEmpty();
        }

        @Test
        @Order(5)
        @DisplayName("count returns correct number")
        void count() {
            long count = executionService.executeCount(
                    compiler.compileCount("posts", Map.of("status", "draft")));
            assertThat(count).isGreaterThanOrEqualTo(0);
        }

        @Test
        @Order(6)
        @DisplayName("insertMany inserts multiple documents")
        void insertMany() {
            var docs = List.of(
                    Map.<String, Object>of("title", "Batch 1", "status", "new"),
                    Map.<String, Object>of("title", "Batch 2", "status", "new"),
                    Map.<String, Object>of("title", "Batch 3", "status", "new"));

            var results = executionService.executeBulkMutation(
                    compiler.compileInsertMany("posts", docs));
            assertThat(results).hasSize(3);

            long count = executionService.executeCount(
                    compiler.compileCount("posts", Map.of("status", "new")));
            assertThat(count).isEqualTo(3);
        }

        @Test
        @Order(7)
        @DisplayName("updateMany updates multiple documents")
        void updateMany() {
            var results = executionService.executeBulkMutation(
                    compiler.compileUpdateMany("posts",
                            Map.of("status", "new"),
                            Map.of("$set", Map.of("status", "archived"))));
            assertThat(results).hasSize(3);

            long count = executionService.executeCount(
                    compiler.compileCount("posts", Map.of("status", "archived")));
            assertThat(count).isEqualTo(3);
        }

        @Test
        @Order(8)
        @DisplayName("deleteMany removes multiple documents")
        void deleteMany() {
            var results = executionService.executeBulkMutation(
                    compiler.compileDeleteMany("posts", Map.of("status", "archived")));
            assertThat(results).hasSize(3);

            long count = executionService.executeCount(
                    compiler.compileCount("posts", Map.of("status", "archived")));
            assertThat(count).isEqualTo(0);
        }
    }

    @Nested
    @Order(3)
    @DisplayName("Index enforcement")
    class IndexEnforcementTests {

        @BeforeEach
        void ensureCollection() {
            if (!schemaManager.getCollectionInfo().hasCollection("enforced")) {
                schemaManager.syncSchema(Map.of("collections", Map.of(
                        "enforced", Map.of(
                                "indexes", List.of(
                                        Map.of("fields", List.of("indexed_field"), "type", "string", "unique", false)
                                )
                        )
                )));
            }
        }

        @Test
        @DisplayName("unindexed field returns warning")
        void unindexedFieldWarns() {
            var schema = schemaManager.getCollectionInfo().getCollection("enforced").get();
            var warnings = schema.checkIndexes(Set.of("not_indexed"));
            assertThat(warnings).hasSize(1);
            assertThat(warnings.getFirst()).contains("not indexed");
        }

        @Test
        @DisplayName("indexed field returns no warning")
        void indexedFieldNoWarning() {
            var schema = schemaManager.getCollectionInfo().getCollection("enforced").get();
            var warnings = schema.checkIndexes(Set.of("indexed_field"));
            assertThat(warnings).isEmpty();
        }
    }
}
