package io.github.excalibase.compiler;

import graphql.language.*;
import io.github.excalibase.SqlDialect;
import io.github.excalibase.schema.SchemaInfo;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Compiles the {@code _vector} GraphQL argument into an ORDER BY + LIMIT
 * fragment for k-nearest-neighbor queries against pgvector columns.
 *
 * <p><strong>Why this lives outside FilterBuilder:</strong> {@code _vector} is
 * not a WHERE predicate — it modifies row ordering and result size. Threading
 * those through FilterBuilder's return type would force a wide ripple across
 * every WHERE-only call site. This builder is invoked by QueryBuilder after
 * FilterBuilder, composing on top of the WHERE fragment without changing it.
 *
 * <p>Expected GraphQL shape:
 * <pre>{@code
 *   _vector: {
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
     * Compile a {@code _vector} input into an ordering clause + bind param.
     *
     * @param vectorArg the GraphQL ObjectValue passed to {@code _vector}
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
        if (schemaInfo != null && !schemaInfo.hasExtension("vector")) {
            // pgvector not installed — silently skip rather than emit invalid SQL.
            return Optional.empty();
        }

        String column = null;
        List<Float> embedding = null;
        String distance = "L2";
        Integer limit = null;

        for (ObjectField f : vectorArg.getObjectFields()) {
            switch (f.getName()) {
                case "column" -> {
                    if (f.getValue() instanceof StringValue sv) column = sv.getValue();
                }
                case "near" -> {
                    if (f.getValue() instanceof ArrayValue av) {
                        embedding = av.getValues().stream()
                                .map(VectorSearchBuilder::toFloat)
                                .toList();
                    }
                }
                case "distance" -> {
                    if (f.getValue() instanceof StringValue sv) distance = sv.getValue();
                    else if (f.getValue() instanceof EnumValue ev) distance = ev.getName();
                }
                case "limit" -> {
                    if (f.getValue() instanceof IntValue iv) limit = iv.getValue().intValue();
                }
            }
        }

        if (column == null || embedding == null || embedding.isEmpty()) {
            return Optional.empty();
        }

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
        // Avoid collision if multiple _vector args exist on the same query.
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
     * Result of compiling a {@code _vector} arg.
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
