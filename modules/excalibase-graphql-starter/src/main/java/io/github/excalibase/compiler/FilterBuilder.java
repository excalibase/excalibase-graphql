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
                            // `contains` is overloaded: on text columns it's
                            // LIKE %pat%, on jsonb columns it's the JSONB
                            // containment operator @>. Dispatch on the
                            // column's Postgres type.
                            String colPgType = schemaInfo != null ? schemaInfo.getColumnType(tableName, col) : null;
                            if (isJsonType(colPgType)) {
                                String p = nextParam("p_" + col + "_jc", params);
                                var sql = dialect.jsonPredicateSql(SqlDialect.JsonPredicate.CONTAINS, colRef, ":" + p);
                                if (sql.isPresent()) {
                                    conditions.add(sql.get());
                                    params.put(p, extractValue(op.getValue()));
                                }
                            } else {
                                String p = nextParam("p_" + col + "_ct", params);
                                conditions.add(colRef + LIKE + PARAM_PREFIX + p);
                                params.put(p, "%" + extractValue(op.getValue()) + "%");
                            }
                        }
                        case FILTER_SEARCH -> {
                            // Plain FTS — plainto_tsquery. Always safe on any
                            // user input. A dialect that returns Optional.empty()
                            // (e.g. MySQL today) silently skips the operator.
                            String p = nextParam("p_" + col + "_search", params);
                            var sql = dialect.fullTextSearchSql(colRef, ":" + p, SqlDialect.FtsVariant.PLAIN);
                            if (sql.isPresent()) {
                                conditions.add(sql.get());
                                params.put(p, extractValue(op.getValue()));
                            }
                        }
                        case FILTER_WEB_SEARCH -> {
                            // websearch_to_tsquery — Google-style syntax
                            // ("quoted phrase" / OR / -exclusion). Also safe
                            // against malformed input.
                            String p = nextParam("p_" + col + "_websearch", params);
                            var sql = dialect.fullTextSearchSql(colRef, ":" + p, SqlDialect.FtsVariant.WEB_SEARCH);
                            if (sql.isPresent()) {
                                conditions.add(sql.get());
                                params.put(p, extractValue(op.getValue()));
                            }
                        }
                        case FILTER_PHRASE_SEARCH -> {
                            // phraseto_tsquery — words must be adjacent in
                            // the document in the given order. Always safe
                            // against malformed input.
                            String p = nextParam("p_" + col + "_phrase", params);
                            var sql = dialect.fullTextSearchSql(colRef, ":" + p, SqlDialect.FtsVariant.PHRASE);
                            if (sql.isPresent()) {
                                conditions.add(sql.get());
                                params.put(p, extractValue(op.getValue()));
                            }
                        }
                        case FILTER_RAW_SEARCH -> {
                            // to_tsquery — raw tsquery syntax. Throws on bad
                            // input, so only use when the input is
                            // known-valid (e.g. server-side generation).
                            String p = nextParam("p_" + col + "_rawts", params);
                            var sql = dialect.fullTextSearchSql(colRef, ":" + p, SqlDialect.FtsVariant.RAW);
                            if (sql.isPresent()) {
                                conditions.add(sql.get());
                                params.put(p, extractValue(op.getValue()));
                            }
                        }
                        case FILTER_REGEX -> {
                            // POSIX regex match, case-sensitive. Postgres
                            // uses `~`. Dialects that don't implement regex
                            // silently drop the operator.
                            String p = nextParam("p_" + col + "_regex", params);
                            var sql = dialect.regexSql(colRef, ":" + p, false);
                            if (sql.isPresent()) {
                                conditions.add(sql.get());
                                params.put(p, extractValue(op.getValue()));
                            }
                        }
                        case FILTER_IREGEX -> {
                            // POSIX regex match, case-insensitive. Postgres
                            // uses `~*`.
                            String p = nextParam("p_" + col + "_iregex", params);
                            var sql = dialect.regexSql(colRef, ":" + p, true);
                            if (sql.isPresent()) {
                                conditions.add(sql.get());
                                params.put(p, extractValue(op.getValue()));
                            }
                        }
                        case FILTER_CONTAINED_BY -> {
                            String p = nextParam("p_" + col + "_jcb", params);
                            var sql = dialect.jsonPredicateSql(SqlDialect.JsonPredicate.CONTAINED_BY, colRef, ":" + p);
                            if (sql.isPresent()) {
                                conditions.add(sql.get());
                                params.put(p, extractValue(op.getValue()));
                            }
                        }
                        case FILTER_HAS_KEY -> {
                            // jsonb_exists(col, :key) — key is a plain String.
                            String p = nextParam("p_" + col + "_hk", params);
                            var sql = dialect.jsonPredicateSql(SqlDialect.JsonPredicate.HAS_KEY, colRef, ":" + p);
                            if (sql.isPresent()) {
                                conditions.add(sql.get());
                                params.put(p, extractValue(op.getValue()));
                            }
                        }
                        case FILTER_HAS_KEYS -> {
                            // jsonb_exists_all(col, ARRAY[:k1, :k2, ...]) —
                            // expand the ArrayValue to individual binds and
                            // build a SQL array literal. The dialect gets
                            // a placeholder expression with the element
                            // refs already spliced in.
                            if (op.getValue() instanceof ArrayValue av) {
                                List<String> elements = new ArrayList<>();
                                for (int i = 0; i < av.getValues().size(); i++) {
                                    String p = nextParam("p_" + col + "_hks" + i, params);
                                    elements.add(":" + p);
                                    params.put(p, extractValue(av.getValues().get(i)));
                                }
                                String arrExpr = "ARRAY[" + joinCols(elements) + "]";
                                var sql = dialect.jsonPredicateSql(SqlDialect.JsonPredicate.HAS_ALL_KEYS, colRef, arrExpr);
                                sql.ifPresent(conditions::add);
                            }
                        }
                        case FILTER_HAS_ANY_KEYS -> {
                            if (op.getValue() instanceof ArrayValue av) {
                                List<String> elements = new ArrayList<>();
                                for (int i = 0; i < av.getValues().size(); i++) {
                                    String p = nextParam("p_" + col + "_hak" + i, params);
                                    elements.add(":" + p);
                                    params.put(p, extractValue(av.getValues().get(i)));
                                }
                                String arrExpr = "ARRAY[" + joinCols(elements) + "]";
                                var sql = dialect.jsonPredicateSql(SqlDialect.JsonPredicate.HAS_ANY_KEYS, colRef, arrExpr);
                                sql.ifPresent(conditions::add);
                            }
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
                        case FILTER_NOT_IN, "nin" -> {
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
    public void applyLimit(StringBuilder sql, Field field, Map<String, Object> params) {
        int limit = maxRows;
        for (Argument arg : field.getArguments()) {
            if (ARG_LIMIT.equals(arg.getName()) || ARG_FIRST.equals(arg.getName())) {
                Integer v = resolveIntArg(arg.getValue(), boundVariables());
                if (v != null) limit = Math.min(v, maxRows);
            }
        }
        String paramName = namedParam(P_LIMIT, params.size());
        sql.append(LIMIT).append(PARAM_PREFIX).append(paramName);
        params.put(paramName, limit);

        for (Argument arg : field.getArguments()) {
            if (ARG_OFFSET.equals(arg.getName())) {
                Integer v = resolveIntArg(arg.getValue(), boundVariables());
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
     * ScopedValue holding the current operation's GraphQL variables map.
     * Bound by {@link SqlCompiler#compile(String, Map)} for the duration of
     * compilation so that nested {@link #extractValue}, {@link #resolveIntArg},
     * and {@link #resolveStringArg} calls can resolve {@code $var} references
     * without threading the map through every method signature.
     */
    public static final ScopedValue<Map<String, Object>> CURRENT_VARIABLES = ScopedValue.newInstance();

    static Map<String, Object> boundVariables() {
        return CURRENT_VARIABLES.isBound() ? CURRENT_VARIABLES.get() : Map.of();
    }

    public Object extractValue(Value<?> value) {
        return extractValue(value, boundVariables());
    }

    /**
     * Converts a GraphQL Value to a Java object, resolving variables from the provided map.
     * ObjectValue and ArrayValue serialize to compact JSON strings so they
     * can be bound directly to Postgres jsonb columns.
     */
    public Object extractValue(Value<?> value, Map<String, Object> variables) {
        if (value instanceof VariableReference vr && variables.containsKey(vr.getName())) {
            return variables.get(vr.getName());
        }
        if (value instanceof IntValue iv) return iv.getValue().intValue();
        if (value instanceof StringValue sv) return sv.getValue();
        if (value instanceof FloatValue fv) return fv.getValue().doubleValue();
        if (value instanceof BooleanValue bv) return bv.isValue();
        if (value instanceof EnumValue ev) return ev.getName();
        if (value instanceof NullValue) return null;
        if (value instanceof ObjectValue ov) return jsonString(ov);
        if (value instanceof ArrayValue av) return jsonString(av);
        return value.toString();
    }

    /**
     * Serializes a GraphQL literal value tree to a compact JSON string —
     * used for binding {@code ObjectValue} / {@code ArrayValue} arguments
     * to Postgres jsonb columns via the {@code contains} / {@code containedBy}
     * / {@code eq} / {@code neq} operators on {@link io.github.excalibase.schema.GraphqlConstants#FILTER_JSON_CONTAINS JsonFilterInput}.
     */
    private String jsonString(Value<?> value) {
        if (value instanceof StringValue sv) return quoteJson(sv.getValue());
        if (value instanceof IntValue iv) return iv.getValue().toString();
        if (value instanceof FloatValue fv) return fv.getValue().toString();
        if (value instanceof BooleanValue bv) return Boolean.toString(bv.isValue());
        if (value instanceof NullValue) return "null";
        if (value instanceof EnumValue ev) return quoteJson(ev.getName());
        if (value instanceof ArrayValue av) {
            StringBuilder sb = new StringBuilder("[");
            @SuppressWarnings("unchecked")
            List<Value<?>> items = (List<Value<?>>) (List<?>) av.getValues();
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(jsonString(items.get(i)));
            }
            return sb.append(']').toString();
        }
        if (value instanceof ObjectValue ov) {
            StringBuilder sb = new StringBuilder("{");
            List<ObjectField> fields = ov.getObjectFields();
            for (int i = 0; i < fields.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(quoteJson(fields.get(i).getName())).append(':').append(jsonString(fields.get(i).getValue()));
            }
            return sb.append('}').toString();
        }
        return quoteJson(value.toString());
    }

    /**
     * True when {@code pgType} names a JSON-family column type. Used by the
     * compile-time dispatch of shared operators like {@code contains}, which
     * have different semantics on text ({@code LIKE}) vs jsonb ({@code @>}).
     */
    private static boolean isJsonType(String pgType) {
        if (pgType == null) return false;
        String t = pgType.toLowerCase();
        return t.equals("json") || t.equals("jsonb") || t.equals("_json") || t.equals("_jsonb");
    }

    private String quoteJson(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
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
