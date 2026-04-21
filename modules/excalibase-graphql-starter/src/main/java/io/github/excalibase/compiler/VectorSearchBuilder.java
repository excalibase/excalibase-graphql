package io.github.excalibase.compiler;

import graphql.language.*;
import io.github.excalibase.SqlDialect;
import io.github.excalibase.schema.SchemaInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Compiles the {@code vector} GraphQL argument into an ORDER BY + LIMIT
 * fragment for k-nearest-neighbor queries against pgvector columns.
 *
 * <p><strong>Why this lives outside FilterBuilder:</strong> {@code vector} is
 * not a WHERE predicate — it modifies row ordering and result size. Threading
 * those through FilterBuilder's return type would force a wide ripple across
 * every WHERE-only call site. This builder is invoked by QueryBuilder after
 * FilterBuilder, composing on top of the WHERE fragment without changing it.
 *
 * <p>Expected GraphQL shape:
 * <pre>{@code
 *   vector: {
 *     column: "embedding",
 *     near:    [0.1, 0.2, 0.3],
 *     distance: "COSINE",   # L2 | COSINE | IP
 *     limit:   10
 *   }
 * }</pre>
 *
 * <p>Returns {@link Optional#empty()} when the dialect doesn't support vector
 * distance operators (MySQL today) or the input is malformed — callers should
 * skip the operator instead of erroring.
 */
public class VectorSearchBuilder {

    private static final String KEY_COLUMN = "column";
    private static final String KEY_DISTANCE = "distance";
    private static final String KEY_LIMIT = "limit";

    private final SqlDialect dialect;

    public VectorSearchBuilder(SqlDialect dialect) {
        this.dialect = dialect;
    }

    /**
     * Compile a {@code vector} input into an ordering clause + bind param.
     *
     * @param vectorArg the GraphQL ObjectValue passed to {@code vector}
     * @param tableAlias the SQL alias for the target table (e.g. {@code "t"})
     * @param schemaInfo schema metadata, used to confirm pgvector is installed
     * @param params mutable bind-parameter map (the embedding is stored here)
     * @return a {@link VectorClause} or empty if vector search is not applicable
     */
    public Optional<VectorClause> build(ObjectValue vectorArg,
                                        String tableAlias,
                                        SchemaInfo schemaInfo,
                                        Map<String, Object> params) {
        if (vectorArg == null) return Optional.empty();
        // Adapter: flatten the GraphQL AST into a plain Map so both GraphQL
        // and REST compile paths share the same core logic.
        Map<String, Object> shape = new HashMap<>();
        for (ObjectField field : vectorArg.getObjectFields()) {
            extractShapeField(field, shape);
        }
        return buildFromMap(shape, tableAlias, schemaInfo, params);
    }

    /** Extract a single GraphQL ObjectField into the flattened shape map. */
    private void extractShapeField(ObjectField field, Map<String, Object> shape) {
        String name = field.getName();
        switch (field.getValue()) {
            case StringValue sv when KEY_COLUMN.equals(name) -> shape.put(KEY_COLUMN, sv.getValue());
            case StringValue sv when KEY_DISTANCE.equals(name) -> shape.put(KEY_DISTANCE, sv.getValue());
            case EnumValue ev when KEY_DISTANCE.equals(name) -> shape.put(KEY_DISTANCE, ev.getName());
            case IntValue iv when KEY_LIMIT.equals(name) -> shape.put(KEY_LIMIT, iv.getValue().intValue());
            case ArrayValue av when "near".equals(name) -> {
                List<Float> floats = new ArrayList<>(av.getValues().size());
                for (Value<?> elementValue : av.getValues()) floats.add(toFloat(elementValue));
                shape.put("near", floats);
            }
            default -> {
                // Ignore unknown vector args or mismatched value types
            }
        }
    }

    /**
     * Core compile path. Accepts a plain Java Map shape so callers that don't
     * have a GraphQL AST (REST compiler, programmatic clients) can reuse the
     * same logic without depending on graphql-java. Expected keys:
     * <ul>
     *   <li>{@code column} — String, required</li>
     *   <li>{@code near} — {@code List<? extends Number>}, required and non-empty</li>
     *   <li>{@code distance} — String, optional (default "L2")</li>
     *   <li>{@code limit} — Integer, optional</li>
     * </ul>
     */
    public Optional<VectorClause> buildFromMap(Map<String, Object> shape,
                                               String tableAlias,
                                               SchemaInfo schemaInfo,
                                               Map<String, Object> params) {
        if (shape == null || shape.isEmpty()) return Optional.empty();
        if (schemaInfo != null && !schemaInfo.hasExtension("vector")) {
            // pgvector not installed — silently skip rather than emit invalid SQL.
            return Optional.empty();
        }

        Object colObj = shape.get(KEY_COLUMN);
        if (!(colObj instanceof String column) || column.isEmpty()) return Optional.empty();

        Optional<List<Float>> embeddingOpt = coerceEmbedding(shape.get("near"));
        if (embeddingOpt.isEmpty()) return Optional.empty();
        List<Float> embedding = embeddingOpt.get();

        String distance = shape.get(KEY_DISTANCE) instanceof String distanceValue ? distanceValue : "L2";
        Integer limit = shape.get(KEY_LIMIT) instanceof Number limitValue ? limitValue.intValue() : null;

        Optional<String> op = dialect.vectorDistanceOperator(distance);
        if (op.isEmpty()) return Optional.empty();

        String finalParam = bindEmbedding(column, embedding, params);
        String colRef = tableAlias + "." + dialect.quoteIdentifier(column);
        String orderBy = colRef + " " + op.get() + " :" + finalParam + "::vector";
        return Optional.of(new VectorClause(orderBy, limit));
    }

    /** Coerce the "near" value into a non-empty List<Float>, or Optional.empty() if malformed. */
    private Optional<List<Float>> coerceEmbedding(Object nearObj) {
        if (!(nearObj instanceof List<?> list) || list.isEmpty()) return Optional.empty();
        List<Float> embedding = new ArrayList<>(list.size());
        for (Object v : list) {
            if (!(v instanceof Number n)) return Optional.empty();
            embedding.add(n.floatValue());
        }
        return Optional.of(embedding);
    }

    /** Bind the embedding as a Postgres vector literal ("[0.1,0.2]") with a unique param name. */
    private String bindEmbedding(String column, List<Float> embedding, Map<String, Object> params) {
        StringBuilder lit = new StringBuilder("[");
        for (int i = 0; i < embedding.size(); i++) {
            if (i > 0) lit.append(',');
            lit.append(embedding.get(i));
        }
        lit.append(']');

        String paramName = "p_vector_" + column;
        int suffix = 0;
        while (params.containsKey(paramName + (suffix == 0 ? "" : "_" + suffix))) suffix++;
        String finalParam = paramName + (suffix == 0 ? "" : "_" + suffix);
        params.put(finalParam, lit.toString());
        return finalParam;
    }

    /**
     * Result of compiling a {@code vector} arg.
     *
     * @param orderByFragment SQL to inject at the front of the ORDER BY list
     *                        (k-NN ordering takes precedence over user-supplied sort)
     * @param limitOverride if non-null, replaces the default LIMIT for this query
     */
    public record VectorClause(String orderByFragment, Integer limitOverride) {}

    private static float toFloat(Value<?> v) {
        if (v instanceof FloatValue fv) return fv.getValue().floatValue();
        if (v instanceof IntValue iv) return iv.getValue().floatValue();
        throw new IllegalArgumentException("vector embedding component must be numeric, got: " + v);
    }
}
