package io.github.excalibase.rest.compiler;

import io.github.excalibase.SqlDialect;
import io.github.excalibase.rest.compiler.RestQueryCompiler.FilterSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.excalibase.compiler.SqlKeywords.*;

/** The SQL predicate of one PostgREST filter, its values bound in the request's parameters. */
final class RestFilterSql {

    private static final int MAX_IN_LIST = 1000;
    private static final String IMATCH = "imatch";

    private final SqlDialect dialect;
    private final ParamValues values;

    RestFilterSql(SqlDialect dialect, ParamValues values) {
        this.dialect = dialect;
        this.values = values;
    }

    String sql(FilterSpec filter, String paramName, Map<String, Object> params, String table) {
        requireSupported(filter);
        String colRef = dialect.quoteIdentifier(filter.column());
        String neg = filter.negated() ? NOT : "";
        String op = filter.operator();

        return switch (op) {
            case "eq", "neq", "gt", "gte", "lt", "lte" ->
                    comparisonSql(op, filter, paramName, params, colRef, neg, table);
            case "like", "ilike", "startswith", "endswith", "match", IMATCH ->
                    patternSql(op, filter, paramName, params, colRef, neg);
            case "is", "isnotnull", "isdistinct" ->
                    nullSql(op, filter, paramName, params, colRef, neg, table);
            case "in", "notin" ->
                    buildInSql(filter, colRef, neg, paramName, params, "notin".equals(op), table);
            case "haskey",
                 "jsoncontains", "contains", "cs",
                 "jsoncontained", "containedin", "cd",
                 "jsonpath", "jsonpathexists",
                 "arraycontains", "arrayhasall",
                 "arrayhasany", "ov" ->
                    jsonOrArraySql(op, filter, paramName, params, colRef, neg, table);
            case "arraylength" -> {
                params.put(paramName, values.convertValue(filter.value(), table, filter.column()));
                yield neg + FN_ARRAY_LENGTH + parens(colRef + COMMA_SEP + "1") + ASSIGN + PARAM_PREFIX + paramName;
            }
            case "fts", "plfts", "phfts", "wfts" ->
                    tsFilter(params, paramName, filter, neg, colRef, ftsVariant(op));
            case "sl", "sr", "nxl", "nxr", "adj" ->
                    rangeSql(op, filter, paramName, params, colRef, neg, table);
            default -> throw new IllegalArgumentException("Unsupported filter operator: " + op);
        };
    }

    /** Refuses an operator the database cannot run; it is never compiled into SQL the database would reject. */
    void requireSupported(FilterSpec filter) {
        if (!dialect.filterCapabilities().restOperators().contains(filter.operator())) {
            throw unsupported(filter);
        }
    }

    private IllegalArgumentException unsupported(FilterSpec filter) {
        return new IllegalArgumentException("Filter operator '" + filter.operator() + "' on column '"
                + filter.column() + "' is not supported on " + dialect.databaseName());
    }

    private String comparisonSql(String op, FilterSpec filter, String paramName,
                                  Map<String, Object> params, String colRef, String neg, String table) {
        String sqlOp = switch (op) {
            case "eq" -> ASSIGN;
            case "neq" -> NEQ;
            case "gt" -> GT;
            case "gte" -> GTE;
            case "lt" -> LT;
            case "lte" -> LTE;
            default -> throw new IllegalStateException(op);
        };
        return comparison(params, paramName, filter, neg, colRef, sqlOp, table);
    }

    private String patternSql(String op, FilterSpec filter, String paramName,
                               Map<String, Object> params, String colRef, String neg) {
        return switch (op) {
            case "like" -> stringFilter(params, paramName, neg, colRef, LIKE, filter.value().replace('*', '%'));
            case "ilike" -> {
                params.put(paramName, filter.value().replace('*', '%'));
                yield neg + dialect.ilike(colRef, PARAM_PREFIX + paramName);
            }
            case "startswith" -> stringFilter(params, paramName, neg, colRef, LIKE, filter.value() + "%");
            case "endswith" -> stringFilter(params, paramName, neg, colRef, LIKE, "%" + filter.value());
            case "match", IMATCH -> {
                params.put(paramName, filter.value());
                yield neg + dialect.regexSql(colRef, PARAM_PREFIX + paramName, IMATCH.equals(op))
                        .orElseThrow(() -> unsupported(filter));
            }
            default -> throw new IllegalStateException(op);
        };
    }

    private String nullSql(String op, FilterSpec filter, String paramName,
                            Map<String, Object> params, String colRef, String neg, String table) {
        return switch (op) {
            case "is" -> isFilter(neg, colRef, filter.value());
            case "isnotnull" -> neg + colRef + IS_NOT_NULL;
            case "isdistinct" -> {
                params.put(paramName, values.convertValue(filter.value(), table, filter.column()));
                yield neg + dialect.isDistinctFrom(colRef, PARAM_PREFIX + paramName);
            }
            default -> throw new IllegalStateException(op);
        };
    }

    /**
     * jsonpath / jsonpathexists use the jsonb_path_exists function form instead of the {@code @?}
     * operator — the {@code ?} character collides with JDBC placeholder parsing in Spring's
     * NamedParameterJdbcTemplate. Array operators bind the raw {@code {a,b,c}} literal via
     * convertValue so Postgres parses it as the column's array type.
     */
    private String jsonOrArraySql(String op, FilterSpec filter, String paramName,
                                    Map<String, Object> params, String colRef, String neg, String table) {
        String paramRef = PARAM_PREFIX + paramName;
        String expr = switch (op) {
            case "haskey" -> json(SqlDialect.JsonPredicate.HAS_KEY, filter, colRef, paramRef);
            case "jsoncontains", "contains", "cs" -> json(SqlDialect.JsonPredicate.CONTAINS, filter, colRef, paramRef);
            case "jsoncontained", "containedin", "cd" ->
                    json(SqlDialect.JsonPredicate.CONTAINED_BY, filter, colRef, paramRef);
            case "jsonpath", "jsonpathexists" -> FN_JSONB_PATH_EXISTS + parens(colRef + COMMA_SEP + PARAM_PREFIX + paramName + CAST_JSONPATH);
            case "arraycontains", "arrayhasall" -> colRef + CONTAINS + PARAM_PREFIX + paramName;
            case "arrayhasany", "ov" -> colRef + OVERLAPS + PARAM_PREFIX + paramName;
            default -> throw new IllegalStateException(op);
        };
        return jsonFilter(params, paramName, filter, neg, expr, table);
    }

    private String json(SqlDialect.JsonPredicate predicate, FilterSpec filter, String colRef, String paramRef) {
        return dialect.jsonPredicateSql(predicate, colRef, paramRef).orElseThrow(() -> unsupported(filter));
    }

    private static SqlDialect.FtsVariant ftsVariant(String op) {
        return switch (op) {
            case "fts" -> SqlDialect.FtsVariant.RAW;
            case "plfts" -> SqlDialect.FtsVariant.PLAIN;
            case "phfts" -> SqlDialect.FtsVariant.PHRASE;
            case "wfts" -> SqlDialect.FtsVariant.WEB_SEARCH;
            default -> throw new IllegalStateException(op);
        };
    }

    private String rangeSql(String op, FilterSpec filter, String paramName,
                             Map<String, Object> params, String colRef, String neg, String table) {
        String rangeOp = switch (op) {
            case "sl" -> STRICTLY_LEFT;
            case "sr" -> STRICTLY_RIGHT;
            case "nxl" -> NO_EXTEND_LEFT;
            case "nxr" -> NO_EXTEND_RIGHT;
            case "adj" -> ADJACENT;
            default -> throw new IllegalStateException(op);
        };
        return jsonFilter(params, paramName, filter, neg, colRef + rangeOp + PARAM_PREFIX + paramName, table);
    }

    private String comparison(Map<String, Object> params, String pn, FilterSpec filter, String neg, String col, String op, String table) {
        params.put(pn, values.convertValue(filter.value(), table, filter.column()));
        return neg + col + op + PARAM_PREFIX + pn;
    }

    private String stringFilter(Map<String, Object> params, String pn, String neg, String col, String op, String val) {
        params.put(pn, val);
        return neg + col + op + PARAM_PREFIX + pn;
    }

    private String jsonFilter(Map<String, Object> params, String pn, FilterSpec filter, String neg, String expr, String table) {
        // Route the bind through convertValue so json/jsonb and array columns
        // get Types.OTHER coercion — Postgres then parses the string against
        // the actual column type. Without this, raw String binds produce
        // `varchar` on both sides and lose against `jsonb` / `text[]`.
        params.put(pn, values.convertValue(filter.value(), table, filter.column()));
        return neg + expr;
    }

    private String tsFilter(Map<String, Object> params, String pn, FilterSpec filter, String neg, String col,
                            SqlDialect.FtsVariant variant) {
        params.put(pn, dialect.fullTextSearchValue(variant, filter.value()));
        return neg + dialect.fullTextSearchSql(dialect.fullTextDocument(col), PARAM_PREFIX + pn, variant)
                .orElseThrow(() -> unsupported(filter));
    }

    private String isFilter(String neg, String col, String value) {
        return switch (value.toLowerCase()) {
            case "true" -> neg + col + IS_TRUE;
            case "false" -> neg + col + IS_FALSE;
            default -> neg + col + IS_NULL; // "null" and anything else → IS NULL check
        };
    }

    private String buildInSql(FilterSpec filter, String col, String neg, String pn, Map<String, Object> params, boolean not, String table) {
        String inner = filter.value();
        if (inner.startsWith("(") && inner.endsWith(")")) inner = inner.substring(1, inner.length() - 1);
        String[] items = inner.split(",");
        if (items.length > MAX_IN_LIST) throw new IllegalArgumentException("IN list exceeds maximum of " + MAX_IN_LIST);
        List<String> ph = new ArrayList<>();
        for (int i = 0; i < items.length; i++) {
            String param = pn + UNDERSCORE + i;
            // Coerce each item through the same type-aware path as eq/neq/gt/…
            // so int columns get Integer bindings and enum columns get a
            // Types.OTHER cast. Without this, `?id=in.(1,2,3)` binds three
            // Strings and Postgres rejects the whole query.
            params.put(param, values.convertValue(items[i].trim(), table, filter.column()));
            ph.add(PARAM_PREFIX + param);
        }
        return neg + col + (not ? NOT_IN : IN) + parens(String.join(COMMA_SEP, ph));
    }
}
