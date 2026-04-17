package io.github.excalibase.nosql.compiler;

import io.github.excalibase.nosql.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentQueryCompilerTest {

    private DocumentQueryCompiler compiler;
    private CollectionInfo collectionInfo;

    @BeforeEach
    void setUp() {
        collectionInfo = new CollectionInfo();
        collectionInfo.addCollection("users", new CollectionSchema(
                "users",
                Map.of("email", FieldType.STRING, "name", FieldType.STRING,
                       "age", FieldType.NUMBER, "status", FieldType.STRING),
                List.of(
                        new IndexDef(List.of("email"), "string", true),
                        new IndexDef(List.of("status"), "string", false),
                        new IndexDef(List.of("age"), "number", false)
                ),
                Set.of("email", "status", "age"),
                null, null));
        compiler = new DocumentQueryCompiler(collectionInfo);
    }

    @Nested
    @DisplayName("compileFind")
    class Find {

        @Test
        @DisplayName("simple string filter")
        void simpleStringFilter() {
            var filter = Map.<String, Object>of("status", "active");
            var result = compiler.compileFind("users", filter, new FindOptions(30, 0, null));

            assertThat(result.sql()).contains("FROM nosql.\"users\"");
            assertThat(result.sql()).contains("(data->>'status') = :p0");
            assertThat(result.params()).containsEntry("p0", "active");
        }

        @Test
        @DisplayName("numeric filter with $gt")
        void numericGtFilter() {
            var filter = Map.<String, Object>of("age", Map.of("$gt", 25));
            var result = compiler.compileFind("users", filter, new FindOptions(30, 0, null));

            assertThat(result.sql()).contains("((data->>'age')::numeric) > :p0");
            assertThat(result.params()).containsEntry("p0", 25);
        }

        @Test
        @DisplayName("multiple filters become AND")
        void multipleFilters() {
            var filter = new LinkedHashMap<String, Object>();
            filter.put("status", "active");
            filter.put("age", Map.of("$gte", 18));
            var result = compiler.compileFind("users", filter, new FindOptions(30, 0, null));

            assertThat(result.sql()).contains("(data->>'status') = :p0");
            assertThat(result.sql()).contains("((data->>'age')::numeric) >= :p1");
            assertThat(result.sql()).contains(" AND ");
        }

        @Test
        @DisplayName("limit and offset")
        void limitOffset() {
            var result = compiler.compileFind("users", Map.of(), new FindOptions(10, 5, null));

            assertThat(result.sql()).contains("LIMIT :limit");
            assertThat(result.sql()).contains("OFFSET :offset");
            assertThat(result.params()).containsEntry("limit", 10);
            assertThat(result.params()).containsEntry("offset", 5);
        }

        @Test
        @DisplayName("sort ascending")
        void sortAsc() {
            var sort = Map.<String, Object>of("age", 1);
            var result = compiler.compileFind("users", Map.of(), new FindOptions(30, 0, sort));

            assertThat(result.sql()).contains("ORDER BY (data->>'age') ASC");
        }

        @Test
        @DisplayName("sort descending")
        void sortDesc() {
            var sort = Map.<String, Object>of("age", -1);
            var result = compiler.compileFind("users", Map.of(), new FindOptions(30, 0, sort));

            assertThat(result.sql()).contains("ORDER BY (data->>'age') DESC");
        }

        @Test
        @DisplayName("empty filter returns all with limit")
        void emptyFilter() {
            var result = compiler.compileFind("users", Map.of(), new FindOptions(30, 0, null));

            assertThat(result.sql()).contains("FROM nosql.\"users\"");
            assertThat(result.sql()).doesNotContain("WHERE");
            assertThat(result.sql()).contains("LIMIT");
        }
    }

    @Nested
    @DisplayName("compileFindOne")
    class FindOne {

        @Test
        @DisplayName("adds LIMIT 1")
        void limitOne() {
            var result = compiler.compileFindOne("users", Map.of("email", "vu@test.com"));

            assertThat(result.sql()).contains("LIMIT 1");
            assertThat(result.sql()).contains("(data->>'email') = :p0");
        }
    }

    @Nested
    @DisplayName("compileGetById")
    class GetById {

        @Test
        @DisplayName("filters by id column")
        void byId() {
            var result = compiler.compileGetById("users", "abc-123");

            assertThat(result.sql()).contains("WHERE id = :id::uuid");
            assertThat(result.params()).containsEntry("id", "abc-123");
            assertThat(result.sql()).contains("LIMIT 1");
        }
    }

    @Nested
    @DisplayName("compileInsertOne")
    class InsertOne {

        @Test
        @DisplayName("inserts data as JSONB with RETURNING")
        void insert() {
            var doc = Map.<String, Object>of("name", "Vu", "email", "vu@test.com");
            var result = compiler.compileInsertOne("users", doc);

            assertThat(result.sql()).contains("INSERT INTO nosql.\"users\" (data)");
            assertThat(result.sql()).contains("VALUES (:data::jsonb)");
            assertThat(result.sql()).contains("RETURNING");
            assertThat(result.params()).containsKey("data");
        }
    }

    @Nested
    @DisplayName("compileUpdateOne")
    class UpdateOne {

        @Test
        @DisplayName("$set merges with || operator")
        void setOperator() {
            var filter = Map.<String, Object>of("email", "vu@test.com");
            var update = Map.<String, Object>of("$set", Map.of("status", "inactive"));
            var result = compiler.compileUpdateOne("users", filter, update);

            assertThat(result.sql()).contains("UPDATE nosql.\"users\"");
            assertThat(result.sql()).contains("data = data || :patch::jsonb");
            assertThat(result.sql()).contains("updated_at = clock_timestamp()");
            assertThat(result.sql()).contains("(data->>'email') = :p1");
            assertThat(result.sql()).contains("RETURNING");
            assertThat(result.params()).containsKey("patch");
            assertThat(result.params()).containsEntry("p1", "vu@test.com");
        }
    }

    @Nested
    @DisplayName("compileDeleteOne")
    class DeleteOne {

        @Test
        @DisplayName("delete with filter and RETURNING")
        void delete() {
            var filter = Map.<String, Object>of("email", "vu@test.com");
            var result = compiler.compileDeleteOne("users", filter);

            assertThat(result.sql()).contains("DELETE FROM nosql.\"users\"");
            assertThat(result.sql()).contains("(data->>'email') = :p0");
            assertThat(result.sql()).contains("RETURNING");
        }
    }

    @Nested
    @DisplayName("compileUpdateMany")
    class UpdateMany {

        @Test
        @DisplayName("updates multiple docs matching filter")
        void updateMany() {
            var filter = Map.<String, Object>of("status", "draft");
            var update = Map.<String, Object>of("$set", Map.of("status", "published"));
            var result = compiler.compileUpdateMany("users", filter, update);

            assertThat(result.sql()).contains("UPDATE nosql.\"users\"");
            assertThat(result.sql()).contains("data = data || :patch::jsonb");
            assertThat(result.sql()).contains("(data->>'status') = :p1");
            assertThat(result.sql()).contains("RETURNING");
        }
    }

    @Nested
    @DisplayName("compileDeleteMany")
    class DeleteMany {

        @Test
        @DisplayName("deletes multiple docs matching filter")
        void deleteMany() {
            var filter = Map.<String, Object>of("status", "trash");
            var result = compiler.compileDeleteMany("users", filter);

            assertThat(result.sql()).contains("DELETE FROM nosql.\"users\"");
            assertThat(result.sql()).contains("(data->>'status') = :p0");
            assertThat(result.sql()).contains("RETURNING");
        }
    }

    @Nested
    @DisplayName("compileSearch")
    class Search {

        @Test
        @DisplayName("FTS search query with ranking")
        void ftsSearch() {
            // Need collection with search field
            collectionInfo.addCollection("articles", new CollectionSchema(
                    "articles",
                    Map.of("title", FieldType.STRING, "body", FieldType.STRING),
                    List.of(new IndexDef(List.of("title"), "string", false)),
                    Set.of("title"),
                    "body", null));

            var result = compiler.compileSearch("articles", "hello world", 10);

            assertThat(result.sql()).contains("FROM nosql.\"articles\"");
            assertThat(result.sql()).contains("search_text @@ websearch_to_tsquery");
            assertThat(result.sql()).contains("ts_rank");
            assertThat(result.sql()).contains("ORDER BY");
            assertThat(result.params()).containsEntry("query", "hello world");
            assertThat(result.params()).containsEntry("limit", 10);
        }
    }

    @Nested
    @DisplayName("compileVectorSearch")
    class VectorSearch {

        @Test
        @DisplayName("vector similarity search with topK")
        void vectorSearch() {
            collectionInfo.addCollection("docs", new CollectionSchema(
                    "docs",
                    Map.of("title", FieldType.STRING),
                    List.of(new IndexDef(List.of("title"), "string", false)),
                    Set.of("title"),
                    null, new VectorDef("embedding", 3)));

            var embedding = List.of(0.1, 0.2, 0.3);
            var result = compiler.compileVectorSearch("docs", embedding, 5);

            assertThat(result.sql()).contains("FROM nosql.\"docs\"");
            assertThat(result.sql()).contains("embedding <=> :embedding::vector");
            assertThat(result.sql()).contains("ORDER BY");
            assertThat(result.params()).containsKey("embedding");
            assertThat(result.params()).containsEntry("topK", 5);
        }
    }

    @Nested
    @DisplayName("Comparison operators")
    class Operators {

        @Test
        @DisplayName("$lt operator")
        void lt() {
            var result = compiler.compileFind("users", Map.of("age", Map.of("$lt", 30)), new FindOptions(30, 0, null));
            assertThat(result.sql()).contains("((data->>'age')::numeric) < :p0");
        }

        @Test
        @DisplayName("$lte operator")
        void lte() {
            var result = compiler.compileFind("users", Map.of("age", Map.of("$lte", 30)), new FindOptions(30, 0, null));
            assertThat(result.sql()).contains("((data->>'age')::numeric) <= :p0");
        }

        @Test
        @DisplayName("$ne operator")
        void ne() {
            var result = compiler.compileFind("users", Map.of("status", Map.of("$ne", "deleted")), new FindOptions(30, 0, null));
            assertThat(result.sql()).contains("(data->>'status') != :p0");
        }

        @Test
        @DisplayName("$in operator")
        void in() {
            var result = compiler.compileFind("users", Map.of("status", Map.of("$in", List.of("active", "pending"))), new FindOptions(30, 0, null));
            assertThat(result.sql()).contains("(data->>'status') IN (:p0)");
        }
    }
}
