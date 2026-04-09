package io.github.excalibase.compiler;

import graphql.language.*;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.SqlDialect;

import java.util.*;
import static io.github.excalibase.schema.GraphqlConstants.*;
import static io.github.excalibase.compiler.SqlKeywords.*;

/**
 * Extracts and applies WHERE, ORDER BY, and LIMIT/OFFSET clauses from GraphQL
 * field arguments. Works with SqlDialect for database-specific syntax.
 */
public class FilterBuilder {

    private final SqlDialect dialect;
    private final int maxRows;
    private SchemaInfo schemaInfo;
    private String dbSchema;

    public FilterBuilder(SqlDialect dialect, int maxRows) {
        this.dialect = dialect;
        this.maxRows = maxRows;
    }

    public FilterBuilder(SqlDialect dialect, int maxRows, SchemaInfo schemaInfo, String dbSchema) {
        this.dialect = dialect;
        this.maxRows = maxRows;
        this.schemaInfo = schemaInfo;
        this.dbSchema = dbSchema;
    }

    // === WHERE ===

    /**
     * Finds the "where" or "filter" argument on the given field and populates conditions.
     */
    public void buildWhereConditions(Field field, String alias, Map<String, Object> params, List<String> conditions) {
        buildWhereConditions(field, alias, params, conditions, null);
    }

    /**
     * Finds the "where" or "filter" argument on the given field and populates conditions,
     * with table name for enum type casting.
     */
    public void buildWhereConditions(Field field, String alias, Map<String, Object> params, List<String> conditions, String tableName) {
        Argument whereArg = field.getArguments().stream()
                .filter(a -> ARG_WHERE.equals(a.getName()) || ARG_FILTER.equals(a.getName()))
                .findFirst().orElse(null);
        if (whereArg == null || !(whereArg.getValue() instanceof ObjectValue ov)) return;

        buildFilterConditions(ov, alias, params, conditions, tableName);
    }

    /**
     * Recursively builds SQL conditions from an ObjectValue containing filter operators.
     * Supported operators: eq, neq, gt, gte, lt, lte, in, nin, like, ilike, startsWith, endsWith, contains, is, or, and, not.
     */
    public void buildFilterConditions(ObjectValue ov, String alias, Map<String, Object> params, List<String> conditions) {
        buildFilterConditions(ov, alias, params, conditions, null);
    }

    public void buildFilterConditions(ObjectValue ov, String alias, Map<String, Object> params, List<String> conditions, String tableName) {
        for (ObjectField of : ov.getObjectFields()) {
            String fieldName = of.getName();

            // Logical operators: or, and, not
            if ("or".equals(fieldName) && of.getValue() instanceof ArrayValue av) {
                List<String> orParts = new ArrayList<>();
                for (Value<?> v : av.getValues()) {
                    if (v instanceof ObjectValue subOv) {
                        List<String> subConds = new ArrayList<>();
                        buildFilterConditions(subOv, alias, params, subConds, tableName);
                        if (!subConds.isEmpty()) {
                            orParts.add("(" + String.join(AND, subConds) + ")");
                        }
                    }
                }
                if (!orParts.isEmpty()) {
                    conditions.add("(" + String.join(OR, orParts) + ")");
                }
                continue;
            }
            if ("and".equals(fieldName) && of.getValue() instanceof ArrayValue av) {
                for (Value<?> v : av.getValues()) {
                    if (v instanceof ObjectValue subOv) {
                        buildFilterConditions(subOv, alias, params, conditions, tableName);
                    }
                }
                continue;
            }
            if ("not".equals(fieldName) && of.getValue() instanceof ObjectValue notOv) {
                List<String> subConds = new ArrayList<>();
                buildFilterConditions(notOv, alias, params, subConds, tableName);
                if (!subConds.isEmpty()) {
                    conditions.add(NOT + "(" + String.join(AND, subConds) + ")");
                }
                continue;
            }

            // Column-level filter: { column: { op: value } }
            String col = fieldName;
            if (of.getValue() instanceof ObjectValue filterObj) {
                // Determine enum cast suffix for this column
                String enumCast = "";
                if (schemaInfo != null && tableName != null) {
                    String enumType = schemaInfo.getEnumType(tableName, col);
                    if (enumType != null) {
                        String resolvedSchema = schemaInfo.resolveSchema(tableName, dbSchema);
                        String rawEnum = enumType.contains(".") ? enumType.substring(enumType.indexOf('.') + 1) : enumType;
                        enumCast = dialect.enumCast(resolvedSchema, rawEnum);
                    }
                }

                // Determine type cast suffix for non-standard types (date, timestamp, etc.)
                String typeCast = "";
                if (schemaInfo != null && tableName != null) {
                    String colType = schemaInfo.getColumnType(tableName, col);
                    if (colType != null) {
                        typeCast = dialect.paramCast(colType);
                    }
                }
                // Enum cast takes precedence over type cast
                String paramCast = !enumCast.isEmpty() ? enumCast : typeCast;

                for (ObjectField op : filterObj.getObjectFields()) {
                    String opName = op.getName();
                    String colRef = alias + "." + dialect.quoteIdentifier(col);

                    switch (opName) {
                        case FILTER_EQ -> {
                            if (op.getValue() instanceof NullValue) {
                                conditions.add(colRef + IS_NULL);
                            } else {
                                String p = nextParam("p_" + col + "_eq", params);
                                conditions.add(colRef + " = :" + p + paramCast);
                                params.put(p, extractValue(op.getValue()));
                            }
                        }
                        case FILTER_NEQ -> {
                            if (op.getValue() instanceof NullValue) {
                                conditions.add(colRef + IS_NOT_NULL);
                            } else {
                                String p = nextParam("p_" + col + "_neq", params);
                                conditions.add(colRef + " != :" + p + paramCast);
                                params.put(p, extractValue(op.getValue()));
                            }
                        }
                        case FILTER_GT -> {
                            String p = nextParam("p_" + col + "_gt", params);
                            conditions.add(colRef + " > :" + p + paramCast);
                            params.put(p, extractValue(op.getValue()));
                        }
                        case FILTER_GTE -> {
                            String p = nextParam("p_" + col + "_gte", params);
                            conditions.add(colRef + " >= :" + p + paramCast);
                            params.put(p, extractValue(op.getValue()));
                        }
                        case FILTER_LT -> {
                            String p = nextParam("p_" + col + "_lt", params);
                            conditions.add(colRef + " < :" + p + paramCast);
                            params.put(p, extractValue(op.getValue()));
                        }
                        case FILTER_LTE -> {
                            String p = nextParam("p_" + col + "_lte", params);
                            conditions.add(colRef + " <= :" + p + paramCast);
                            params.put(p, extractValue(op.getValue()));
                        }
                        case FILTER_IN -> {
                            if (op.getValue() instanceof ArrayValue av) {
                                List<String> inParams = new ArrayList<>();
                                for (int i = 0; i < av.getValues().size(); i++) {
                                    String p = nextParam("p_" + col + "_in" + i, params);
                                    inParams.add(":" + p + paramCast);
                                    params.put(p, extractValue(av.getValues().get(i)));
                                }
                                conditions.add(colRef + IN + "(" + joinCols(inParams) + ")");
                            }
                        }
                        case "nin" -> {
                            if (op.getValue() instanceof ArrayValue av) {
                                List<String> inParams = new ArrayList<>();
                                for (int i = 0; i < av.getValues().size(); i++) {
                                    String p = nextParam("p_" + col + "_nin" + i, params);
                                    inParams.add(":" + p + paramCast);
                                    params.put(p, extractValue(av.getValues().get(i)));
                                }
                                conditions.add(colRef + " NOT IN (" + joinCols(inParams) + ")");
                            }
                        }
                        case FILTER_LIKE -> {
                            String p = nextParam("p_" + col + "_like", params);
                            conditions.add(colRef + LIKE + PARAM_PREFIX + p);
                            params.put(p, extractValue(op.getValue()));
                        }
                        case FILTER_ILIKE -> {
                            String p = nextParam("p_" + col + "_ilike", params);
                            conditions.add(dialect.ilike(colRef, param(p)));
                            params.put(p, extractValue(op.getValue()));
                        }
                        case FILTER_STARTS_WITH -> {
                            String p = nextParam("p_" + col + "_sw", params);
                            conditions.add(colRef + LIKE + PARAM_PREFIX + p);
                            params.put(p, extractValue(op.getValue()) + "%");
                        }
                        case FILTER_ENDS_WITH -> {
                            String p = nextParam("p_" + col + "_ew", params);
                            conditions.add(colRef + LIKE + PARAM_PREFIX + p);
                            params.put(p, "%" + extractValue(op.getValue()));
                        }
                        case FILTER_CONTAINS -> {
                            String p = nextParam("p_" + col + "_ct", params);
                            conditions.add(colRef + LIKE + PARAM_PREFIX + p);
                            params.put(p, "%" + extractValue(op.getValue()) + "%");
                        }
                        case "is" -> {
                            // { is: NULL } or { is: NOT_NULL }
                            String v = op.getValue() instanceof EnumValue ev ? ev.getName() : extractValue(op.getValue()).toString();
                            if ("NULL".equalsIgnoreCase(v)) {
                                conditions.add(colRef + IS_NULL);
                            } else if ("NOT_NULL".equalsIgnoreCase(v)) {
                                conditions.add(colRef + IS_NOT_NULL);
                            }
                        }
                        case FILTER_IS_NULL -> {
                            // { isNull: true } → IS NULL, { isNull: false } → IS NOT NULL
                            Object v = extractValue(op.getValue());
                            if (Boolean.TRUE.equals(v) || "true".equals(String.valueOf(v))) {
                                conditions.add(colRef + IS_NULL);
                            } else {
                                conditions.add(colRef + IS_NOT_NULL);
                            }
                        }
                        case FILTER_IS_NOT_NULL -> {
                            // { isNotNull: true } → IS NOT NULL, { isNotNull: false } → IS NULL
                            Object v = extractValue(op.getValue());
                            if (Boolean.TRUE.equals(v) || "true".equals(String.valueOf(v))) {
                                conditions.add(colRef + IS_NOT_NULL);
                            } else {
                                conditions.add(colRef + IS_NULL);
                            }
                        }
                        case FILTER_NOT_IN -> {
                            if (op.getValue() instanceof ArrayValue av) {
                                List<String> inParams = new ArrayList<>();
                                for (int i = 0; i < av.getValues().size(); i++) {
                                    String p = nextParam("p_" + col + "_notin" + i, params);
                                    inParams.add(":" + p + paramCast);
                                    params.put(p, extractValue(av.getValues().get(i)));
                                }
                                conditions.add(colRef + " NOT IN (" + joinCols(inParams) + ")");
                            }
                        }
                        default -> {
                            String p = nextParam("p_" + col + "_" + opName, params);
                            conditions.add(colRef + " = :" + p + paramCast);
                            params.put(p, extractValue(op.getValue()));
                        }
                    }
                }
            }
        }
    }

    /**
     * Appends a WHERE clause to the SQL builder from the field's where/filter argument.
     */
    public void applyWhere(StringBuilder sql, Field field, String alias, Map<String, Object> params) {
        applyWhere(sql, field, alias, params, null);
    }

    /**
     * Appends a WHERE clause with table name for enum type casting.
     */
    public void applyWhere(StringBuilder sql, Field field, String alias, Map<String, Object> params, String tableName) {
        List<String> conditions = new ArrayList<>();
        buildWhereConditions(field, alias, params, conditions, tableName);
        if (!conditions.isEmpty()) {
            sql.append(WHERE).append(String.join(AND, conditions));
        }
    }

    // === ORDER BY ===

    /**
     * Appends an ORDER BY clause to the SQL builder from the field's orderBy argument.
     */
    public void applyOrderBy(StringBuilder sql, Field field, String alias) {
        Argument orderByArg = field.getArguments().stream()
                .filter(a -> ARG_ORDER_BY.equals(a.getName()))
                .findFirst().orElse(null);

        if (orderByArg == null || !(orderByArg.getValue() instanceof ObjectValue ov)) return;

        List<String> clauses = new ArrayList<>();
        for (ObjectField of : ov.getObjectFields()) {
            String dir;
            if (of.getValue() instanceof EnumValue ev) {
                dir = ev.getName();
            } else if (of.getValue() instanceof StringValue sv) {
                dir = sv.getValue();
            } else {
                dir = ASC;
            }
            String colRef = alias + "." + dialect.quoteIdentifier(of.getName());
            String orderClause = parseOrderDirection(colRef, dir);
            clauses.add(orderClause);
        }
        if (!clauses.isEmpty()) {
            sql.append(ORDER_BY).append(joinCols(clauses));
        }
    }

    /**
     * Parses the orderBy argument into a list of [column, direction] pairs.
     */
    public List<String[]> parseOrderBy(Field field) {
        List<String[]> result = new ArrayList<>();
        Argument orderByArg = field.getArguments().stream()
                .filter(a -> ARG_ORDER_BY.equals(a.getName()))
                .findFirst().orElse(null);
        if (orderByArg != null && orderByArg.getValue() instanceof ObjectValue ov) {
            for (ObjectField of : ov.getObjectFields()) {
                String dir;
                if (of.getValue() instanceof EnumValue ev) {
                    dir = ev.getName();
                } else if (of.getValue() instanceof StringValue sv) {
                    dir = sv.getValue();
                } else {
                    dir = ASC;
                }
                result.add(new String[]{of.getName(), dir.toUpperCase()});
            }
        }
        return result;
    }

    /**
     * Parses extended order direction values like AscNullsLast, DescNullsFirst.
     */
    private String parseOrderDirection(String colRef, String dir) {
        String upper = dir.toUpperCase();
        return switch (upper) {
            case "ASCNULLSLAST" -> dialect.orderByNulls(colRef, ASC, "LAST");
            case "ASCNULLSFIRST" -> dialect.orderByNulls(colRef, ASC, "FIRST");
            case "DESCNULLSLAST" -> dialect.orderByNulls(colRef, DESC, "LAST");
            case "DESCNULLSFIRST" -> dialect.orderByNulls(colRef, DESC, "FIRST");
            default -> colRef + " " + dir;
        };
    }

    // === LIMIT / OFFSET ===

    /**
     * Appends LIMIT and OFFSET clauses to the SQL builder, capped by maxRows.
     */
    public void applyLimit(StringBuilder sql, Field field, String alias, Map<String, Object> params) {
        int limit = maxRows;
        for (Argument arg : field.getArguments()) {
            if (ARG_LIMIT.equals(arg.getName()) || ARG_FIRST.equals(arg.getName())) {
                Integer v = resolveIntArg(arg.getValue(), Map.of());
                if (v != null) limit = Math.min(v, maxRows);
            }
        }
        String paramName = namedParam(P_LIMIT, params.size());
        sql.append(LIMIT).append(PARAM_PREFIX).append(paramName);
        params.put(paramName, limit);

        for (Argument arg : field.getArguments()) {
            if (ARG_OFFSET.equals(arg.getName())) {
                Integer v = resolveIntArg(arg.getValue(), Map.of());
                if (v != null) {
                    String offParam = "offset_" + params.size();
                    sql.append(" OFFSET :").append(offParam);
                    params.put(offParam, v);
                }
            }
        }
    }

    // === Parameter helpers ===

    /**
     * Generates a unique parameter name using the prefix and current param map size.
     */
    public String nextParam(String prefix, Map<String, Object> params) {
        return prefix + "_" + params.size();
    }

    // === Value extraction ===

    /**
     * Converts a GraphQL Value to a Java object (no variable resolution).
     */
    public Object extractValue(Value<?> value) {
        return extractValue(value, Map.of());
    }

    /**
     * Converts a GraphQL Value to a Java object, resolving variables from the provided map.
     */
    public Object extractValue(Value<?> value, Map<String, Object> variables) {
        if (value instanceof VariableReference vr && variables.containsKey(vr.getName())) {
            return variables.get(vr.getName());
        }
        if (value instanceof IntValue iv) return iv.getValue().intValue();
        if (value instanceof StringValue sv) return sv.getValue();
        if (value instanceof FloatValue fv) return fv.getValue().doubleValue();
        if (value instanceof BooleanValue bv) return bv.isValue();
        if (value instanceof EnumValue ev) return ev.getName().toLowerCase();
        if (value instanceof NullValue) return null;
        return value.toString();
    }

    /**
     * Resolves an integer from a GraphQL Value, supporting variable references.
     */
    public Integer resolveIntArg(Value<?> value, Map<String, Object> variables) {
        if (value instanceof VariableReference vr && variables.containsKey(vr.getName())) {
            Object resolved = variables.get(vr.getName());
            if (resolved instanceof Number n) return n.intValue();
            return null;
        }
        if (value instanceof IntValue iv) return iv.getValue().intValue();
        return null;
    }

    /**
     * Resolves a string from a GraphQL Value, supporting variable references.
     */
    public String resolveStringArg(Value<?> value, Map<String, Object> variables) {
        if (value instanceof VariableReference vr && variables.containsKey(vr.getName())) {
            Object resolved = variables.get(vr.getName());
            return resolved != null ? resolved.toString() : null;
        }
        if (value instanceof StringValue sv) return sv.getValue();
        return null;
    }

    /**
     * Returns the configured maximum rows.
     */
    public int getMaxRows() {
        return maxRows;
    }
}
