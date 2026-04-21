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

    /**
     * Handle logical operators (or/and/not) on a single ObjectField.
     * Returns true if the field was a logical operator and has been consumed.
     */
    private boolean applyLogicalOperator(ObjectField of, String alias, Map<String, Object> params,
                                         List<String> conditions, String tableName) {
        String fieldName = of.getName();
        return switch (fieldName) {
            case "or"  -> applyOrOperator(of, alias, params, conditions, tableName);
            case "and" -> applyAndOperator(of, alias, params, conditions, tableName);
            case "not" -> applyNotOperator(of, alias, params, conditions, tableName);
            default    -> false;
        };
    }

    private boolean applyOrOperator(ObjectField of, String alias, Map<String, Object> params,
                                    List<String> conditions, String tableName) {
        if (!(of.getValue() instanceof ArrayValue av)) return false;
        List<String> orParts = new ArrayList<>();
        for (Value<?> elementValue : av.getValues()) {
            if (elementValue instanceof ObjectValue subOv) {
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
        return true;
    }

    private boolean applyAndOperator(ObjectField of, String alias, Map<String, Object> params,
                                     List<String> conditions, String tableName) {
        if (!(of.getValue() instanceof ArrayValue av)) return false;
        for (Value<?> elementValue : av.getValues()) {
            if (elementValue instanceof ObjectValue subOv) {
                buildFilterConditions(subOv, alias, params, conditions, tableName);
            }
        }
        return true;
    }

    private boolean applyNotOperator(ObjectField of, String alias, Map<String, Object> params,
                                     List<String> conditions, String tableName) {
        if (!(of.getValue() instanceof ObjectValue notOv)) return false;
        List<String> subConds = new ArrayList<>();
        buildFilterConditions(notOv, alias, params, subConds, tableName);
        if (!subConds.isEmpty()) {
            conditions.add(NOT + "(" + String.join(AND, subConds) + ")");
        }
        return true;
    }

    public void buildFilterConditions(ObjectValue ov, String alias, Map<String, Object> params, List<String> conditions, String tableName) {
        for (ObjectField of : ov.getObjectFields()) {
            if (applyLogicalOperator(of, alias, params, conditions, tableName)) continue;
            if (of.getValue() instanceof ObjectValue filterObj) {
                applyColumnFilter(of.getName(), filterObj, alias, params, conditions, tableName);
            }
        }
    }

    /**
     * Applies all operators under a single column filter object to the conditions list.
     * e.g. {@code first_name: { eq: "MARY", neq: "JOHN" }}
     */
    private void applyColumnFilter(String col, ObjectValue filterObj, String alias,
                                   Map<String, Object> params, List<String> conditions, String tableName) {
        String paramCast = resolveParamCast(col, tableName);
        String colRef = alias + "." + dialect.quoteIdentifier(col);

        for (ObjectField op : filterObj.getObjectFields()) {
            dispatchOperator(op, col, colRef, paramCast, params, conditions, tableName);
        }
    }

    /** Resolves the parameter cast suffix (enum cast takes precedence over generic type cast). */
    private String resolveParamCast(String col, String tableName) {
        if (schemaInfo == null || tableName == null) return "";
        String enumType = schemaInfo.getEnumType(tableName, col);
        if (enumType != null) {
            String resolvedSchema = schemaInfo.resolveSchema(tableName, dbSchema);
            String rawEnum = enumType.contains(".") ? enumType.substring(enumType.indexOf('.') + 1) : enumType;
            String enumCast = dialect.enumCast(resolvedSchema, rawEnum);
            if (!enumCast.isEmpty()) return enumCast;
        }
        String colType = schemaInfo.getColumnType(tableName, col);
        return colType != null ? dialect.paramCast(colType) : "";
    }

    /** Dispatches a single operator to its matching handler group. */
    private void dispatchOperator(ObjectField op, String col, String colRef, String paramCast,
                                  Map<String, Object> params, List<String> conditions, String tableName) {
        String opName = op.getName();
        switch (opName) {
            case FILTER_EQ, FILTER_NEQ, FILTER_GT, FILTER_GTE, FILTER_LT, FILTER_LTE ->
                    applyComparison(opName, op, col, colRef, paramCast, params, conditions);
            case FILTER_IN, FILTER_NOT_IN, "nin" ->
                    applyInList(opName, op, col, colRef, paramCast, params, conditions);
            case FILTER_LIKE, FILTER_ILIKE, FILTER_STARTS_WITH, FILTER_ENDS_WITH, FILTER_CONTAINS ->
                    applyStringPattern(opName, op, col, colRef, params, conditions, tableName);
            case FILTER_SEARCH, FILTER_WEB_SEARCH, FILTER_PHRASE_SEARCH, FILTER_RAW_SEARCH ->
                    applyFullTextSearch(opName, op, col, colRef, params, conditions);
            case FILTER_REGEX, FILTER_IREGEX ->
                    applyRegex(opName, op, col, colRef, params, conditions);
            case FILTER_CONTAINED_BY, FILTER_HAS_KEY, FILTER_HAS_KEYS, FILTER_HAS_ANY_KEYS ->
                    applyJsonPredicate(opName, op, col, colRef, params, conditions);
            case "is", FILTER_IS_NULL, FILTER_IS_NOT_NULL ->
                    applyNullPredicate(opName, op, colRef, conditions);
            default -> {
                String paramName = nextParam("p_" + col + "_" + opName, params);
                conditions.add(colRef + " = :" + paramName + paramCast);
                params.put(paramName, extractValue(op.getValue()));
            }
        }
    }

    /** Handles eq, neq, gt, gte, lt, lte. eq/neq special-case NULL literal. */
    private void applyComparison(String opName, ObjectField op, String col, String colRef,
                                 String paramCast, Map<String, Object> params, List<String> conditions) {
        if (FILTER_EQ.equals(opName) && op.getValue() instanceof NullValue) {
            conditions.add(colRef + IS_NULL);
            return;
        }
        if (FILTER_NEQ.equals(opName) && op.getValue() instanceof NullValue) {
            conditions.add(colRef + IS_NOT_NULL);
            return;
        }
        String sqlOp = switch (opName) {
            case FILTER_EQ -> " = ";
            case FILTER_NEQ -> " != ";
            case FILTER_GT -> " > ";
            case FILTER_GTE -> " >= ";
            case FILTER_LT -> " < ";
            case FILTER_LTE -> " <= ";
            default -> " = ";
        };
        String suffix = switch (opName) {
            case FILTER_EQ -> "_eq";
            case FILTER_NEQ -> "_neq";
            case FILTER_GT -> "_gt";
            case FILTER_GTE -> "_gte";
            case FILTER_LT -> "_lt";
            case FILTER_LTE -> "_lte";
            default -> "_" + opName;
        };
        String paramName = nextParam("p_" + col + suffix, params);
        conditions.add(colRef + sqlOp + ":" + paramName + paramCast);
        params.put(paramName, extractValue(op.getValue()));
    }

    /** Handles FILTER_IN, FILTER_NOT_IN, "nin". */
    private void applyInList(String opName, ObjectField op, String col, String colRef,
                             String paramCast, Map<String, Object> params, List<String> conditions) {
        if (!(op.getValue() instanceof ArrayValue av)) return;
        boolean negate = FILTER_NOT_IN.equals(opName) || "nin".equals(opName);
        String suffix = negate ? "_notin" : "_in";
        List<String> inParams = new ArrayList<>();
        for (int i = 0; i < av.getValues().size(); i++) {
            String paramName = nextParam("p_" + col + suffix + i, params);
            inParams.add(":" + paramName + paramCast);
            params.put(paramName, extractValue(av.getValues().get(i)));
        }
        conditions.add(colRef + (negate ? " NOT IN (" : IN + "(") + joinCols(inParams) + ")");
    }

    /** Handles LIKE, ILIKE, startsWith, endsWith, contains (text variant). */
    private void applyStringPattern(String opName, ObjectField op, String col, String colRef,
                                    Map<String, Object> params, List<String> conditions, String tableName) {
        Object raw = extractValue(op.getValue());
        switch (opName) {
            case FILTER_LIKE -> {
                String paramName = nextParam("p_" + col + "_like", params);
                conditions.add(colRef + LIKE + PARAM_PREFIX + paramName);
                params.put(paramName, raw);
            }
            case FILTER_ILIKE -> {
                String paramName = nextParam("p_" + col + "_ilike", params);
                conditions.add(dialect.ilike(colRef, param(paramName)));
                params.put(paramName, raw);
            }
            case FILTER_STARTS_WITH -> {
                String paramName = nextParam("p_" + col + "_sw", params);
                conditions.add(colRef + LIKE + PARAM_PREFIX + paramName);
                params.put(paramName, raw + "%");
            }
            case FILTER_ENDS_WITH -> {
                String paramName = nextParam("p_" + col + "_ew", params);
                conditions.add(colRef + LIKE + PARAM_PREFIX + paramName);
                params.put(paramName, "%" + raw);
            }
            case FILTER_CONTAINS -> applyContains(op, col, colRef, raw, params, conditions, tableName);
            default -> { /* unreachable */ }
        }
    }

    /**
     * `contains` is overloaded: on text columns it's LIKE %pat%, on jsonb columns
     * it's the JSONB containment operator @>. Dispatch on the column's Postgres type.
     */
    private void applyContains(ObjectField op, String col, String colRef, Object raw,
                               Map<String, Object> params, List<String> conditions, String tableName) {
        String colPgType = schemaInfo != null ? schemaInfo.getColumnType(tableName, col) : null;
        if (isJsonType(colPgType)) {
            String paramName = nextParam("p_" + col + "_jc", params);
            var sql = dialect.jsonPredicateSql(SqlDialect.JsonPredicate.CONTAINS, colRef, ":" + paramName);
            if (sql.isPresent()) {
                conditions.add(sql.get());
                params.put(paramName, extractValue(op.getValue()));
            }
        } else {
            String paramName = nextParam("p_" + col + "_ct", params);
            conditions.add(colRef + LIKE + PARAM_PREFIX + paramName);
            params.put(paramName, "%" + raw + "%");
        }
    }

    /** Handles FILTER_SEARCH/WEB_SEARCH/PHRASE_SEARCH/RAW_SEARCH via dialect.fullTextSearchSql. */
    private void applyFullTextSearch(String opName, ObjectField op, String col, String colRef,
                                     Map<String, Object> params, List<String> conditions) {
        SqlDialect.FtsVariant variant = switch (opName) {
            case FILTER_SEARCH -> SqlDialect.FtsVariant.PLAIN;
            case FILTER_WEB_SEARCH -> SqlDialect.FtsVariant.WEB_SEARCH;
            case FILTER_PHRASE_SEARCH -> SqlDialect.FtsVariant.PHRASE;
            case FILTER_RAW_SEARCH -> SqlDialect.FtsVariant.RAW;
            default -> SqlDialect.FtsVariant.PLAIN;
        };
        String suffix = switch (opName) {
            case FILTER_SEARCH -> "_search";
            case FILTER_WEB_SEARCH -> "_websearch";
            case FILTER_PHRASE_SEARCH -> "_phrase";
            case FILTER_RAW_SEARCH -> "_rawts";
            default -> "_fts";
        };
        String paramName = nextParam("p_" + col + suffix, params);
        var sql = dialect.fullTextSearchSql(colRef, ":" + paramName, variant);
        if (sql.isPresent()) {
            conditions.add(sql.get());
            params.put(paramName, extractValue(op.getValue()));
        }
    }

    /** Handles FILTER_REGEX (case-sensitive) and FILTER_IREGEX (case-insensitive) POSIX regex. */
    private void applyRegex(String opName, ObjectField op, String col, String colRef,
                            Map<String, Object> params, List<String> conditions) {
        boolean caseInsensitive = FILTER_IREGEX.equals(opName);
        String suffix = caseInsensitive ? "_iregex" : "_regex";
        String paramName = nextParam("p_" + col + suffix, params);
        var sql = dialect.regexSql(colRef, ":" + paramName, caseInsensitive);
        if (sql.isPresent()) {
            conditions.add(sql.get());
            params.put(paramName, extractValue(op.getValue()));
        }
    }

    /** Handles CONTAINED_BY, HAS_KEY, HAS_KEYS, HAS_ANY_KEYS jsonb predicates. */
    private void applyJsonPredicate(String opName, ObjectField op, String col, String colRef,
                                    Map<String, Object> params, List<String> conditions) {
        switch (opName) {
            case FILTER_CONTAINED_BY -> applyJsonScalar("p_" + col + "_jcb",
                    SqlDialect.JsonPredicate.CONTAINED_BY, op, colRef, params, conditions);
            case FILTER_HAS_KEY -> applyJsonScalar("p_" + col + "_hk",
                    SqlDialect.JsonPredicate.HAS_KEY, op, colRef, params, conditions);
            case FILTER_HAS_KEYS -> applyJsonKeysArray("p_" + col + "_hks",
                    SqlDialect.JsonPredicate.HAS_ALL_KEYS, op, colRef, params, conditions);
            case FILTER_HAS_ANY_KEYS -> applyJsonKeysArray("p_" + col + "_hak",
                    SqlDialect.JsonPredicate.HAS_ANY_KEYS, op, colRef, params, conditions);
            default -> { /* unreachable */ }
        }
    }

    private void applyJsonScalar(String paramPrefix, SqlDialect.JsonPredicate predicate, ObjectField op,
                                 String colRef, Map<String, Object> params, List<String> conditions) {
        String paramName = nextParam(paramPrefix, params);
        var sql = dialect.jsonPredicateSql(predicate, colRef, ":" + paramName);
        if (sql.isPresent()) {
            conditions.add(sql.get());
            params.put(paramName, extractValue(op.getValue()));
        }
    }

    private void applyJsonKeysArray(String paramPrefix, SqlDialect.JsonPredicate predicate, ObjectField op,
                                    String colRef, Map<String, Object> params, List<String> conditions) {
        if (!(op.getValue() instanceof ArrayValue av)) return;
        List<String> elements = new ArrayList<>();
        for (int i = 0; i < av.getValues().size(); i++) {
            String paramName = nextParam(paramPrefix + i, params);
            elements.add(":" + paramName);
            params.put(paramName, extractValue(av.getValues().get(i)));
        }
        String arrExpr = "ARRAY[" + joinCols(elements) + "]";
        var sql = dialect.jsonPredicateSql(predicate, colRef, arrExpr);
        sql.ifPresent(conditions::add);
    }

    /** Handles "is" (enum literal), FILTER_IS_NULL, FILTER_IS_NOT_NULL. */
    private void applyNullPredicate(String opName, ObjectField op, String colRef, List<String> conditions) {
        if ("is".equals(opName)) {
            String enumValue = op.getValue() instanceof EnumValue ev ? ev.getName() : extractValue(op.getValue()).toString();
            if ("NULL".equalsIgnoreCase(enumValue)) {
                conditions.add(colRef + IS_NULL);
            } else if ("NOT_NULL".equalsIgnoreCase(enumValue)) {
                conditions.add(colRef + IS_NOT_NULL);
            }
            return;
        }
        Object value = extractValue(op.getValue());
        boolean truthy = Boolean.TRUE.equals(value) || "true".equals(String.valueOf(value));
        boolean wantsNull = FILTER_IS_NULL.equals(opName) == truthy;
        conditions.add(colRef + (wantsNull ? IS_NULL : IS_NOT_NULL));
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
        String upper = dir == null ? "" : dir.toUpperCase();
        return switch (upper) {
            case "ASCNULLSLAST" -> dialect.orderByNulls(colRef, ASC, "LAST");
            case "ASCNULLSFIRST" -> dialect.orderByNulls(colRef, ASC, "FIRST");
            case "DESCNULLSLAST" -> dialect.orderByNulls(colRef, DESC, "LAST");
            case "DESCNULLSFIRST" -> dialect.orderByNulls(colRef, DESC, "FIRST");
            case "ASC" -> colRef + " " + ASC;
            case "DESC" -> colRef + " " + DESC;
            default -> throw new IllegalArgumentException("Invalid ORDER BY direction: " + dir);
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
                Integer value = resolveIntArg(arg.getValue(), boundVariables());
                if (value != null) limit = Math.min(value, maxRows);
            }
        }
        String paramName = namedParam(P_LIMIT, params.size());
        sql.append(LIMIT).append(PARAM_PREFIX).append(paramName);
        params.put(paramName, limit);

        for (Argument arg : field.getArguments()) {
            if (ARG_OFFSET.equals(arg.getName())) {
                Integer value = resolveIntArg(arg.getValue(), boundVariables());
                if (value != null) {
                    String offParam = "offset_" + params.size();
                    sql.append(" OFFSET :").append(offParam);
                    params.put(offParam, value);
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
        if (value instanceof ArrayValue av) return jsonStringFromArray(av);
        if (value instanceof ObjectValue ov) return jsonStringFromObject(ov);
        return quoteJson(value.toString());
    }

    private String jsonStringFromArray(ArrayValue av) {
        StringBuilder sb = new StringBuilder("[");
        @SuppressWarnings("unchecked")
        List<Value<?>> items = (List<Value<?>>) (List<?>) av.getValues();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(jsonString(items.get(i)));
        }
        return sb.append(']').toString();
    }

    private String jsonStringFromObject(ObjectValue ov) {
        StringBuilder sb = new StringBuilder("{");
        List<ObjectField> fields = ov.getObjectFields();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(quoteJson(fields.get(i).getName())).append(':').append(jsonString(fields.get(i).getValue()));
        }
        return sb.append('}').toString();
    }

    /**
     * True when {@code pgType} names a JSON-family column type. Used by the
     * compile-time dispatch of shared operators like {@code contains}, which
     * have different semantics on text ({@code LIKE}) vs jsonb ({@code @>}).
     */
    private static boolean isJsonType(String pgType) {
        if (pgType == null) return false;
        String type = pgType.toLowerCase();
        return type.equals("json") || type.equals("jsonb") || type.equals("_json") || type.equals("_jsonb");
    }

    private String quoteJson(String input) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
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
            if (resolved instanceof Number number) return number.intValue();
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
