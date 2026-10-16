package io.github.excalibase.rest.compiler;

import io.github.excalibase.postgres.PostgresDialect;
import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Postgres SQL of every REST shape, pinned byte for byte so MySQL support cannot change it. */
class RestPostgresSqlSnapshotTest {

    private RestQueryCompiler compiler;

    @BeforeEach
    void setUp() {
        SchemaInfo schema = new SchemaInfo();
        schema.addColumn("public.products", "id", "integer");
        schema.addColumn("public.products", "name", "text");
        schema.addColumn("public.products", "price", "numeric");
        schema.addColumn("public.products", "metadata", "jsonb");
        schema.addColumn("public.products", "tags", "_text");
        schema.addColumn("public.products", "created_at", "timestamptz");
        schema.addPrimaryKey("public.products", "id");
        schema.setTableSchema("public.products", "public");
        schema.addColumn("public.orders", "id", "integer");
        schema.addColumn("public.orders", "product_id", "integer");
        schema.addColumn("public.orders", "quantity", "integer");
        schema.addPrimaryKey("public.orders", "id");
        schema.setTableSchema("public.orders", "public");
        schema.addForeignKey("public.orders", "product_id", "public.products", "id");
        compiler = new RestQueryCompiler(schema, new PostgresDialect(), "public", 30);
    }

    private static RestQueryCompiler.FilterSpec filter(String column, String operator, String value) {
        return new RestQueryCompiler.FilterSpec(column, operator, value, false);
    }

    private Map<String, String> compileAll() {
        Map<String, String> sql = new LinkedHashMap<>();
        sql.put("select_all", compiler.compileSelect("public.products", List.of(), List.of(), null, 30, 0, false).sql());
        sql.put("select_columns_count", compiler.compileSelect("public.products", List.of("id", "name"), List.of(),
                null, 10, 5, true).sql());
        List<RestQueryCompiler.FilterSpec> filters = List.of(filter("id", "gt", "1"), filter("name", "ilike", "*a*"),
                filter("name", "match", "^a"), filter("name", "imatch", "^a"), filter("price", "isdistinct", "2"),
                filter("price", "is", "null"), filter("id", "in", "(1,2)"),
                new RestQueryCompiler.FilterSpec("name", "like", "a*", true), filter("metadata", "cs", "{}"),
                filter("tags", "ov", "{a}"), filter("name", "fts", "a"));
        List<RestQueryCompiler.OrderBySpec> order = List.of(new RestQueryCompiler.OrderBySpec("price", "DESC", "NULLS LAST"),
                new RestQueryCompiler.OrderBySpec("name", "ASC", "NULLS FIRST"), new RestQueryCompiler.OrderBySpec("id", "ASC", null));
        sql.put("select_filters_order", compiler.compileSelect(new RestQueryCompiler.SelectQuery("public.products",
                List.of(), filters, List.of(new RestQueryCompiler.OrCondition(List.of(filter("id", "eq", "1"),
                filter("id", "eq", "2")))), List.of(), order, 30, 0, true)).sql());
        var nested = new RestQueryCompiler.EmbedSpec("orders", List.of("id", "quantity"), null,
                List.of(new RestQueryCompiler.EmbedSpec("products", List.of("name"))));
        sql.put("select_embeds", compiler.compileSelect(new RestQueryCompiler.SelectQuery("public.products",
                List.of("id"), List.of(), null, List.of(nested), null, 30, 0, false)).sql());
        sql.put("select_forward_embed", compiler.compileSelect(new RestQueryCompiler.SelectQuery("public.orders",
                List.of("id"), List.of(), null, List.of(new RestQueryCompiler.EmbedSpec("products", List.of())), null,
                30, 0, false)).sql());
        sql.put("select_cursor", compiler.compileSelect(new RestQueryCompiler.SelectQuery("public.products", List.of(),
                List.of(), null, List.of(), order, 11, 0, false, "3", "id")).sql());
        sql.put("insert", compiler.compileInsert("public.products", Map.of("name", "a")).sql());
        sql.put("bulk_insert", compiler.compileBulkInsert("public.products",
                List.of(Map.of("name", "a"), Map.of("name", "b"))).sql());
        Map<String, Object> upsert = new LinkedHashMap<>();
        upsert.put("id", 1);
        upsert.put("name", "a");
        sql.put("upsert", compiler.compileUpsert("public.products", upsert, List.of("id")).sql());
        sql.put("upsert_nothing", compiler.compileUpsert("public.products", Map.of("id", 1), List.of("id")).sql());
        sql.put("update", compiler.compileUpdate("public.products", Map.of("name", "b"),
                List.of(filter("id", "eq", "1"))).sql());
        sql.put("delete", compiler.compileDelete("public.products", List.of(filter("id", "eq", "1"))).sql());
        return sql;
    }

    @Test
    void postgresSqlIsUnchanged() throws IOException {
        Map<String, String> expected = load();
        Map<String, String> actual = compileAll();
        assertEquals(expected.keySet(), actual.keySet());
        expected.forEach((name, sql) -> assertEquals(sql, actual.get(name), name));
    }

    private static Map<String, String> load() throws IOException {
        try (InputStream in = Objects.requireNonNull(RestPostgresSqlSnapshotTest.class
                .getResourceAsStream("/rest-postgres-sql.snapshot"))) {
            Map<String, String> snapshot = new LinkedHashMap<>();
            List<String> lines = new ArrayList<>(new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList());
            for (int i = 0; i + 1 < lines.size(); i += 2) {
                snapshot.put(lines.get(i), lines.get(i + 1));
            }
            return snapshot;
        }
    }
}
