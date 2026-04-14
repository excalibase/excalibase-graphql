package io.github.excalibase.compiler;

import graphql.language.*;
import io.github.excalibase.SqlDialect;
import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class VectorSearchBuilderTest {

    /** Minimal stub dialect that mirrors PostgresDialect's vector operators
     *  without pulling in the postgres module (cyclic dep). */
    private static class StubPgDialect implements SqlDialect {
        @Override public String buildObject(List<String> kv) { return ""; }
        @Override public String aggregateArray(String e) { return ""; }
        @Override public String coalesceArray(String e) { return ""; }
        @Override public String quoteIdentifier(String id) { return "\"" + id + "\""; }
        @Override public String qualifiedTable(String s, String t) { return s + ".\"" + t + "\""; }
        @Override public String encodeCursor(String e) { return ""; }
        @Override public String ilike(String c, String p) { return c + " ILIKE " + p; }
        @Override public String orderByNulls(String c, String d, String n) { return c + " " + d; }
        @Override public String suffixCast(String t) { return ""; }
        @Override public String onConflict(List<String> a, List<String> b) { return ""; }
        @Override public boolean supportsReturning() { return true; }
        @Override public String cteName(String a, String s) { return a + s; }
        @Override public String randAlias() { return "r"; }
        @Override public String distinctOn(List<String> c, String a) { return ""; }
        @Override public Optional<String> vectorDistanceOperator(String distance) {
            if (distance == null) return Optional.empty();
            return switch (distance.toUpperCase()) {
                case "L2" -> Optional.of("<->");
                case "COSINE" -> Optional.of("<=>");
                case "IP" -> Optional.of("<#>");
                default -> Optional.empty();
            };
        }
    }

    private final SqlDialect dialect = new StubPgDialect();
    private final VectorSearchBuilder builder = new VectorSearchBuilder(dialect);

    private SchemaInfo schemaWithPgvector() {
        SchemaInfo s = new SchemaInfo();
        s.addExtension("vector", "0.6.0");
        return s;
    }

    private ObjectValue vectorArg(String column, List<Float> embedding, String distance, Integer limit) {
        var fields = new ArrayList<ObjectField>();
        if (column != null) fields.add(new ObjectField("column", new StringValue(column)));
        if (embedding != null) {
            List<Value> values = new ArrayList<>();
            for (Float f : embedding) values.add(new FloatValue(BigDecimal.valueOf(f)));
            fields.add(new ObjectField("near", new ArrayValue(values)));
        }
        if (distance != null) fields.add(new ObjectField("distance", new StringValue(distance)));
        if (limit != null) fields.add(new ObjectField("limit", new IntValue(BigInteger.valueOf(limit))));
        return ObjectValue.newObjectValue().objectFields(fields).build();
    }

    @Test
    void l2Distance_emitsCorrectOperator() {
        var arg = vectorArg("embedding", List.of(0.1f, 0.2f, 0.3f), "L2", 5);
        Map<String, Object> params = new HashMap<>();

        var clause = builder.build(arg, "t", schemaWithPgvector(), params);

        assertTrue(clause.isPresent());
        assertEquals("t.\"embedding\" <-> :p_vector_embedding::vector", clause.get().orderByFragment());
        assertEquals(5, clause.get().limitOverride());
        assertEquals("[0.1,0.2,0.3]", params.get("p_vector_embedding"));
    }

    @Test
    void cosineDistance_emitsCorrectOperator() {
        var arg = vectorArg("embedding", List.of(0.1f, 0.2f), "COSINE", 10);
        Map<String, Object> params = new HashMap<>();

        var clause = builder.build(arg, "t", schemaWithPgvector(), params);

        assertTrue(clause.isPresent());
        assertTrue(clause.get().orderByFragment().contains(" <=> "));
    }

    @Test
    void innerProductDistance_emitsCorrectOperator() {
        var arg = vectorArg("embedding", List.of(0.1f, 0.2f), "IP", null);
        Map<String, Object> params = new HashMap<>();

        var clause = builder.build(arg, "t", schemaWithPgvector(), params);

        assertTrue(clause.isPresent());
        assertTrue(clause.get().orderByFragment().contains(" <#> "));
        assertNull(clause.get().limitOverride(), "limit is optional");
    }

    @Test
    void unknownDistance_returnsEmpty() {
        var arg = vectorArg("embedding", List.of(0.1f), "MANHATTAN", 5);
        Map<String, Object> params = new HashMap<>();

        var clause = builder.build(arg, "t", schemaWithPgvector(), params);

        assertTrue(clause.isEmpty());
    }

    @Test
    void missingPgvectorExtension_returnsEmpty() {
        var arg = vectorArg("embedding", List.of(0.1f), "L2", 5);
        Map<String, Object> params = new HashMap<>();

        // SchemaInfo without the "vector" extension installed
        var clause = builder.build(arg, "t", new SchemaInfo(), params);

        assertTrue(clause.isEmpty(), "must skip when pgvector is absent");
    }

    @Test
    void missingColumn_returnsEmpty() {
        var arg = vectorArg(null, List.of(0.1f, 0.2f), "L2", 5);
        Map<String, Object> params = new HashMap<>();

        var clause = builder.build(arg, "t", schemaWithPgvector(), params);

        assertTrue(clause.isEmpty());
    }

    @Test
    void emptyEmbedding_returnsEmpty() {
        var arg = vectorArg("embedding", List.of(), "L2", 5);
        Map<String, Object> params = new HashMap<>();

        var clause = builder.build(arg, "t", schemaWithPgvector(), params);

        assertTrue(clause.isEmpty());
    }

    @Test
    void multipleVectorClauses_uniqueParamNames() {
        Map<String, Object> params = new HashMap<>();
        params.put("p_vector_embedding", "existing");

        var arg = vectorArg("embedding", List.of(0.5f), "L2", 1);
        var clause = builder.build(arg, "t", schemaWithPgvector(), params);

        assertTrue(clause.isPresent());
        // Param name must not collide with the pre-existing one
        assertTrue(clause.get().orderByFragment().contains("p_vector_embedding_1"));
        assertEquals("[0.5]", params.get("p_vector_embedding_1"));
    }
}
