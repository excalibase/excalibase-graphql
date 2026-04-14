package io.github.excalibase.compiler;

import graphql.language.*;
import io.github.excalibase.SqlDialect;
import io.github.excalibase.schema.SchemaInfo;

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
        Map<String, Object> shape = new java.util.HashMap<>();
        for (ObjectField f : vectorArg.getObjectFields()) {
            switch (f.getName()) {
                case "column" -> {
                    if (f.getValue() instanceof StringValue sv) shape.put("column", sv.getValue());
                }
                case "near" -> {
                    if (f.getValue() instanceof ArrayValue av) {
                        List<Float> floats = new java.util.ArrayList<>(av.getValues().size());
                        for (Value<?> v : av.getValues()) floats.add(toFloat(v));
                        shape.put("near", floats);
                    }
                }
                case "distance" -> {
                    if (f.getValue() instanceof StringValue sv) shape.put("distance", sv.getValue());
                    else if (f.getValue() instanceof EnumValue ev) shape.put("distance", ev.getName());
                }
                case "limit" -> {
                    if (f.getValue() instanceof IntValue iv) shape.put("limit", iv.getValue().intValue());
                }
            }
        }
        return buildFromMap(shape, tableAlias, schemaInfo, params);
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
    @SuppressWarnings("unchecked")
    public Optional<VectorClause> buildFromMap(Map<String, Object> shape,
                                               String tableAlias,
                                               SchemaInfo schemaInfo,
                                               Map<String, Object> params) {
        if (shape == null || shape.isEmpty()) return Optional.empty();
        if (schemaInfo != null && !schemaInfo.hasExtension("vector")) {
            // pgvector not installed — silently skip rather than emit invalid SQL.
            return Optional.empty();
        }

        Object colObj = shape.get("column");
        if (!(colObj instanceof String column) || column.isEmpty()) return Optional.empty();

        Object nearObj = shape.get("near");
        List<Float> embedding = null;
        if (nearObj instanceof List<?> list && !list.isEmpty()) {
            embedding = new java.util.ArrayList<>(list.size());
            for (Object v : list) {
                if (v instanceof Number n) embedding.add(n.floatValue());
                else return Optional.empty(); // malformed — skip rather than crash
            }
        }
        if (embedding == null || embedding.isEmpty()) return Optional.empty();

        String distance = shape.get("distance") instanceof String d ? d : "L2";
        Integer limit = shape.get("limit") instanceof Number n ? n.intValue() : null;

        Optional<String> op = dialect.vectorDistanceOperator(distance);
        if (op.isEmpty()) return Optional.empty();

        // Bind the embedding as a Postgres vector literal: '[0.1,0.2,0.3]'
        StringBuilder lit = new StringBuilder("[");
        for (int i = 0; i < embedding.size(); i++) {
            if (i > 0) lit.append(',');
            lit.append(embedding.get(i));
        }
        lit.append(']');

        String paramName = "p_vector_" + column;
        // Avoid collision if multiple vector args exist on the same query.
        int suffix = 0;
        while (params.containsKey(paramName + (suffix == 0 ? "" : "_" + suffix))) suffix++;
        String finalParam = paramName + (suffix == 0 ? "" : "_" + suffix);
        params.put(finalParam, lit.toString());

        String colRef = tableAlias + "." + dialect.quoteIdentifier(column);
        // Cast the bind param to vector so pgvector can parse the JSON-array literal.
        String orderBy = colRef + " " + op.get() + " :" + finalParam + "::vector";

        return Optional.of(new VectorClause(orderBy, limit));
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
