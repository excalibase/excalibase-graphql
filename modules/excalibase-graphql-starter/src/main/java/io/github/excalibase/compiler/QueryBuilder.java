package io.github.excalibase.compiler;

import graphql.language.*;
import io.github.excalibase.schema.ExposedFunction;
import io.github.excalibase.schema.NamingUtils;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.security.RlsOp;
import io.github.excalibase.SqlDialect;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import static io.github.excalibase.schema.GraphqlConstants.*;
import static io.github.excalibase.compiler.SqlKeywords.*;

/**
 * Handles query compilation: list, connection (CTE-based cursor pagination),
 * aggregate, and recursive JSON object building with FK traversal.
 * Extracted from SqlCompiler to separate query logic from parsing/routing.
 */
public class QueryBuilder {

    private static final String CTE_HAS_NEXT = "_has_next";
    private static final String TYPENAME = "__typename";
    private static final Pattern GRAPHQL_NAME = Pattern.compile("[_A-Za-z]\\w*");

    private final SchemaInfo schemaInfo;
    private final SqlDialect dialect;
    private final FilterBuilder filterBuilder;
    private final VectorSearchBuilder vectorSearchBuilder;
    private final String dbSchema;
    @SuppressWarnings("java:S5164") // ThreadLocal lifecycle is owned by SqlCompiler which calls remove() in finally
    private final ThreadLocal<Map<String, FragmentDefinition>> fragmentsHolder;

    public QueryBuilder(SchemaInfo schemaInfo, SqlDialect dialect, FilterBuilder filterBuilder,
                        String dbSchema, int maxRows,
                        ThreadLocal<Map<String, FragmentDefinition>> fragmentsHolder) {
        this.schemaInfo = schemaInfo;
        this.dialect = dialect;
        this.filterBuilder = filterBuilder;
        this.vectorSearchBuilder = new VectorSearchBuilder(dialect);
        this.dbSchema = dbSchema;
        this.fragmentsHolder = fragmentsHolder;
    }

    /** Resolve the qualified table expression using per-table schema metadata. */
    private String qualifiedTable(String tableName) {
        String schema = schemaInfo.resolveSchema(tableName, dbSchema);
        String rawTable = tableName.contains(".") ? tableName.substring(tableName.indexOf('.') + 1) : tableName;
        return dialect.qualifiedTable(schema, rawTable);
    }

    // === List query ===

    public String compileList(Field field, String tableName, Map<String, Object> params) {
        return compileRows(field, tableName, qualifiedTable(tableName), params);
    }

    /**
     * The rows a tracked function returns, read like a list of its return table: the role's select
     * filter on that table, its columns and its row cap apply, as do the caller's where, orderBy,
     * limit and offset. A single-row function answers one object or null.
     */
    public String compileFunction(Field field, ExposedFunction function, String callSql, Map<String, Object> params) {
        if (function.returnsSet()) {
            return compileRows(field, function.returnTable(), callSql, params);
        }
        String alias = dialect.randAlias();
        String objectSql = buildObject(field.getSelectionSet(), function.returnTable(), alias, params);
        List<String> conditions = new ArrayList<>();
        // A single-row function that returns NULL still yields one row, with every column NULL.
        conditions.add("NOT (" + alias + " IS NULL)");
        filterBuilder.buildWhereConditions(field, alias, params, conditions, function.returnTable());
        return SELECT + objectSql + FROM + "(" + SELECT + alias + ".*" + FROM + callSql + " " + alias
                + WHERE + String.join(AND, conditions) + LIMIT + "1) " + alias;
    }

    /** A list read of {@code tableName}'s rows from {@code source}: the table itself, or a function call. */
    private String compileRows(Field field, String tableName, String source, Map<String, Object> params) {
        String alias = dialect.randAlias();
        String objectSql = buildObject(field.getSelectionSet(), tableName, alias, params);

        // Parse distinctOn argument
        List<String> distinctOnCols = parseDistinctOn(field);
        distinctOnCols.forEach(column -> filterBuilder.requireColumn(tableName, column));

        // Parse vector argument (k-NN search). When present, it takes precedence
        // over user-supplied orderBy and limit — the embedding similarity order
        // IS the sort. Absent/invalid input returns Optional.empty() and we fall
        // through to the normal ORDER BY / LIMIT path.
        Optional<VectorSearchBuilder.VectorClause> vectorClause = extractVectorClause(field, tableName, alias, params);

        StringBuilder sql = new StringBuilder();
        sql.append(SELECT).append(dialect.coalesceArray(dialect.aggregateArray(objectSql)));
        sql.append(FROM).append("(").append(SELECT);

        if (!distinctOnCols.isEmpty()) {
            sql.append(dialect.distinctOn(distinctOnCols, alias)).append(" ");
        }

        sql.append(alias).append(".*");
        sql.append(FROM).append(source).append(" ").append(alias);

        // WHERE from arguments
        filterBuilder.applyWhere(sql, field, alias, params, tableName);

        appendListOrderBy(sql, field, alias, tableName, distinctOnCols, vectorClause);
        appendListLimit(sql, field, tableName, params, vectorClause);

        sql.append(") ").append(alias);
        return sql.toString();
    }

    /** ORDER BY precedence: vector > distinctOn > user-supplied orderBy. */
    private void appendListOrderBy(StringBuilder sql, Field field, String alias, String tableName,
                                   List<String> distinctOnCols,
                                   Optional<VectorSearchBuilder.VectorClause> vectorClause) {
        if (vectorClause.isPresent()) {
            sql.append(ORDER_BY).append(vectorClause.get().orderByFragment());
        } else if (!distinctOnCols.isEmpty()) {
            sql.append(ORDER_BY).append(joinCols(buildDistinctOnOrderClauses(field, alias, tableName, distinctOnCols)));
        } else {
            filterBuilder.applyOrderBy(sql, field, alias, tableName);
        }
    }

    /** DISTINCT ON requires distinct columns first; then append any user ORDER BY columns not already covered. */
    private List<String> buildDistinctOnOrderClauses(Field field, String alias, String tableName,
                                                     List<String> distinctOnCols) {
        List<String> orderClauses = new ArrayList<>();
        for (String col : distinctOnCols) {
            orderClauses.add(alias + "." + dialect.quoteIdentifier(col) + " " + ASC);
        }
        for (String[] column : filterBuilder.parseOrderBy(field, tableName)) {
            if (!distinctOnCols.contains(column[0])) {
                orderClauses.add(filterBuilder.orderClause(alias + "." + dialect.quoteIdentifier(column[0]), column[1]));
            }
        }
        return orderClauses;
    }

    /** LIMIT precedence: vector.limitOverride > user/argument limit. */
    private void appendListLimit(StringBuilder sql, Field field, String tableName, Map<String, Object> params,
                                 Optional<VectorSearchBuilder.VectorClause> vectorClause) {
        if (vectorClause.isPresent() && vectorClause.get().limitOverride() != null) {
            int vlimit = Math.min(vectorClause.get().limitOverride(), filterBuilder.rowCap(tableName));
            String paramName = "p_limit_" + params.size();
            sql.append(LIMIT).append(":").append(paramName);
            params.put(paramName, vlimit);
        } else {
            filterBuilder.applyLimit(sql, field, params, tableName);
        }
    }

    /**
     * Extracts the {@code vector: {...}} argument from a table field, compiles
     * it via {@link VectorSearchBuilder}, and returns the resulting clause.
     * Silently returns empty when the argument is absent, malformed, or the
     * dialect/schema doesn't support vector search — the caller falls through
     * to the normal ORDER BY / LIMIT path.
     */
    private Optional<VectorSearchBuilder.VectorClause> extractVectorClause(
            Field field, String tableName, String alias, Map<String, Object> params) {
        Argument vectorArg = field.getArguments().stream()
                .filter(a -> ARG_VECTOR.equals(a.getName()))
                .findFirst().orElse(null);
        if (vectorArg == null || !(filterBuilder.resolvedValue(vectorArg.getValue()) instanceof ObjectValue ov)) {
            return Optional.empty();
        }
        ov.getObjectFields().stream()
                .filter(vectorField -> "column".equals(vectorField.getName()))
                .forEach(vectorField -> filterBuilder.requireColumn(tableName,
                        String.valueOf(filterBuilder.extractValue(vectorField.getValue()))));
        return vectorSearchBuilder.build(ov, alias, schemaInfo, params);
    }

    private List<String> parseDistinctOn(Field field) {
        List<String> cols = new ArrayList<>();
        for (Argument arg : field.getArguments()) {
            if (ARG_DISTINCT_ON.equals(arg.getName())
                    && filterBuilder.resolvedValue(arg.getValue()) instanceof ArrayValue av) {
                for (Value<?> v : av.getValues()) {
                    switch (v) {
                        case StringValue sv -> cols.add(sv.getValue());
                        case EnumValue ev -> cols.add(ev.getName());
                        default -> throw new IllegalArgumentException("Argument 'distinctOn' takes column names");
                    }
                }
            }
        }
        return cols;
    }

    // === Aggregates ===

    public String compileAggregate(Field field, String tableName, Map<String, Object> params) {
        requireAggregations(field.getName(), tableName);
        return SELECT + aggregateObject(field, tableName, alias -> List.of(), params);
    }

    /** Aggregates exist only for a role allowed them on the table; for anyone else the field is unknown. */
    private void requireAggregations(String fieldName, String tableName) {
        if (!filterBuilder.access().allowsAggregations(tableName)) {
            throw unknownField(fieldName);
        }
    }

    /**
     * The aggregate object of {@code field} over the rows of {@code tableName} that meet {@code scope} (the
     * join to a parent row, if any), the field's {@code where} and the role's select filter.
     */
    private String aggregateObject(Field field, String tableName, Function<String, List<String>> scope,
                                   Map<String, Object> params) {
        List<String> parts = new ArrayList<>();
        for (Selection<?> sel : field.getSelectionSet().getSelections()) {
            if (!(sel instanceof Field aggField)) continue;
            String aggName = aggField.getName();

            if (FIELD_COUNT.equals(aggName)) {
                String alias = dialect.randAlias();
                parts.add(jsonKey(aggField) + ", (" + SELECT + COUNT_ALL
                        + scopedRows(field, tableName, alias, scope, params) + ")");
            } else if (Set.of(AGG_SUM, AGG_AVG, AGG_MIN, AGG_MAX).contains(aggName) && aggField.getSelectionSet() != null) {
                // Nested per-column aggregate -- sum(total_amount), avg(total_amount), etc.
                List<String> colParts = new ArrayList<>();
                for (Selection<?> colSel : aggField.getSelectionSet().getSelections()) {
                    if (colSel instanceof Field colField && !TYPENAME.equals(colField.getName())) {
                        String col = colField.getName();
                        filterBuilder.requireColumn(tableName, col);
                        String subAlias = dialect.randAlias();
                        String aggregate = aggName + "(" + subAlias + "." + dialect.quoteIdentifier(col) + ")";
                        if (AGG_MIN.equals(aggName) || AGG_MAX.equals(aggName)) {
                            // MIN and MAX keep the column's type, so they read as the column does.
                            aggregate = dialect.jsonValue(aggregate, schemaInfo.getColumnType(tableName, col));
                        }
                        colParts.add(jsonKey(colField) + ", (" + SELECT + aggregate
                                + scopedRows(field, tableName, subAlias, scope, params) + ")");
                    }
                }
                parts.add(jsonKey(aggField) + ", " + dialect.buildObject(colParts));
            } else {
                requireTypename(aggName);
            }
        }
        return dialect.buildObject(parts);
    }

    /** {@code FROM table alias WHERE …}: the scope, the field's where and the role's select filter. */
    private String scopedRows(Field field, String tableName, String alias, Function<String, List<String>> scope,
                              Map<String, Object> params) {
        List<String> conditions = new ArrayList<>(scope.apply(alias));
        filterBuilder.buildWhereConditions(field, alias, params, conditions, tableName);
        return FROM + qualifiedTable(tableName) + " " + alias
                + (conditions.isEmpty() ? "" : WHERE + String.join(AND, conditions));
    }

    // === Connection (CTE-based cursor pagination) ===

    public String compileConnection(Field field, String tableName, Map<String, Object> params) {
        String block = dialect.randAlias();
        String pk = getPk(tableName);

        ConnectionSelections sel = parseConnectionSelections(field);
        requireConnectionAllowed(tableName, pk, sel);
        PaginationArgs pagination = parsePaginationArgs(field, tableName);
        List<SortKey> sortKeys = sortKeys(field, tableName, pk);

        boolean isForward = (pagination.last == null);
        int forwardLimit = (pagination.first != null) ? pagination.first : filterBuilder.rowCap(tableName);
        int limit = isForward ? forwardLimit : pagination.last;

        // === Build CTE-based Connection SQL ===
        StringBuilder sql = new StringBuilder();
        sql.append(WITH);

        String recordsCte = dialect.cteName(block, "_records");
        String pageCte = dialect.cteName(block, "_page");
        ConnectionCtx ctx = new ConnectionCtx(block, tableName, recordsCte, pageCte, sortKeys, isForward, limit,
                pagination);
        appendRecordsCte(sql, field, ctx, params);
        appendPageCte(sql, ctx, params);

        boolean needsHasNext = sel.wantsPageInfo
                && (sel.pageInfoFields.isEmpty() || sel.pageInfoFields.containsValue(FIELD_HAS_NEXT_PAGE));
        boolean needsHasPrev = sel.wantsPageInfo
                && (sel.pageInfoFields.isEmpty() || sel.pageInfoFields.containsValue(FIELD_HAS_PREVIOUS_PAGE));
        appendOptionalCtes(sql, field, tableName, ctx, sel, needsHasNext, needsHasPrev, params);

        // === Final SELECT from CTEs ===
        String edgesSub = buildEdgesSubquery(ctx, sel, params);

        List<String> rootParts = new ArrayList<>();
        if (sel.keys.containsKey(FIELD_EDGES)) {
            rootParts.add(jsonKey(sel.keys.get(FIELD_EDGES)) + ", " + dialect.coalesceArray("(" + edgesSub + ")"));
        }
        if (sel.wantsTotalCount) {
            rootParts.add(jsonKey(sel.keys.get(FIELD_TOTAL_COUNT)) + ", (" + SELECT + "val" + FROM
                    + dialect.cteName(block, "_total") + ")");
        }
        if (sel.wantsPageInfo) {
            rootParts.add(jsonKey(sel.keys.get(FIELD_PAGE_INFO)) + ", " + buildPageInfoObject(ctx, sel.pageInfoFields,
                    needsHasNext, needsHasPrev));
        }

        sql.append(" ").append(SELECT).append(dialect.buildObject(rootParts));
        return sql.toString();
    }

    /**
     * One key a connection is ordered by: its column, direction and where its NULLs sort. The primary key
     * always ends the list, so every row has one position and a cursor names it exactly.
     */
    private record SortKey(String column, boolean descending, boolean nullsLast) {
        SortKey reversed() {
            return new SortKey(column, !descending, !nullsLast);
        }
    }

    /** The requested order, NULLs last ascending and first descending unless asked otherwise, then the key. */
    private List<SortKey> sortKeys(Field field, String tableName, String pk) {
        List<SortKey> keys = new ArrayList<>();
        for (String[] orderColumn : filterBuilder.parseOrderBy(field, tableName)) {
            String dir = orderColumn[1];
            boolean descending = dir.startsWith(DESC);
            boolean nullsLast = switch (dir) {
                case "ASC", "ASCNULLSLAST", "DESCNULLSLAST" -> true;
                case "DESC", "ASCNULLSFIRST", "DESCNULLSFIRST" -> false;
                default -> throw new IllegalArgumentException("Invalid ORDER BY direction: " + dir);
            };
            keys.add(new SortKey(orderColumn[0], descending, nullsLast));
        }
        if (keys.stream().noneMatch(key -> key.column().equals(pk))) {
            keys.add(new SortKey(pk, false, true));
        }
        return List.copyOf(keys);
    }

    /** ORDER BY terms of {@code keys} on {@code alias}; NULL placement is spelled out so every database agrees. */
    private String orderTerms(String alias, List<SortKey> keys, boolean reversed) {
        List<String> terms = new ArrayList<>();
        for (SortKey key : keys) {
            SortKey effective = reversed ? key.reversed() : key;
            String col = alias + "." + dialect.quoteIdentifier(key.column());
            terms.add("(" + col + IS_NULL + ") " + (effective.nullsLast() ? ASC : DESC));
            terms.add(col + " " + (effective.descending() ? DESC : ASC));
        }
        return joinCols(terms);
    }

    /** A row's cursor: its sort-key values and primary key as a JSON array, base64-encoded. */
    private String cursorExpr(String alias, List<SortKey> keys) {
        return dialect.encodeCursor(dialect.jsonArray(keys.stream()
                .map(key -> alias + "." + dialect.quoteIdentifier(key.column())).toList()));
    }

    private void appendOptionalCtes(StringBuilder sql, Field field, String tableName, ConnectionCtx ctx,
                                    ConnectionSelections sel, boolean needsHasNext, boolean needsHasPrev,
                                    Map<String, Object> params) {
        if (needsHasNext) {
            appendHasNextCte(sql, ctx.block, ctx.recordsCte, ctx.limit, params);
        }
        if (needsHasPrev) {
            appendHasPrevCte(sql, ctx.block, ctx.pagination.afterCursor, ctx.isForward);
        }
        if (sel.wantsTotalCount) {
            appendTotalCountCte(sql, field, tableName, ctx.block, params);
        }
    }

    /**
     * Cursors are primary-key values and {@code totalCount} is an aggregate, so a connection needs a
     * key the caller can read, and a count needs the role's aggregation right.
     */
    private void requireConnectionAllowed(String tableName, String pk, ConnectionSelections sel) {
        filterBuilder.requireColumn(tableName, pk);
        if (sel.wantsTotalCount && !filterBuilder.access().allowsAggregations(tableName)) {
            throw new IllegalArgumentException("Unknown field 'totalCount' on " + tableName);
        }
    }

    /**
     * Which connection sub-fields the caller requested; {@code keys} holds each one's response key (by
     * field name: edges, node, cursor, totalCount, pageInfo) and {@code pageInfoFields} each pageInfo
     * field's name by response key.
     */
    private record ConnectionSelections(SelectionSet edgesNodeSS, boolean wantsCursor,
                                        boolean wantsTotalCount, boolean wantsPageInfo,
                                        Map<String, String> pageInfoFields, Map<String, String> keys) {}

    /** Pagination argument values extracted from the Field. */
    private record PaginationArgs(Integer first, Integer last, String afterCursor, String beforeCursor) {}

    /** Shared connection-scoped state passed to the per-CTE helpers. */
    private record ConnectionCtx(String block, String tableName, String recordsCte, String pageCte,
                                 List<SortKey> sortKeys, boolean isForward, int limit,
                                 PaginationArgs pagination) {}

    private ConnectionSelections parseConnectionSelections(Field field) {
        SelectionSet edgesNodeSS = null;
        boolean wantsCursor = false;
        boolean wantsTotalCount = false;
        boolean wantsPageInfo = false;
        Map<String, String> pageInfoFields = new LinkedHashMap<>();
        Map<String, String> keys = new HashMap<>();

        for (Selection<?> selection : field.getSelectionSet().getSelections()) {
            if (!(selection instanceof Field childField)) continue;
            String fname = childField.getName();
            if (FIELD_EDGES.equals(fname)) {
                keys.put(FIELD_EDGES, responseKey(childField));
                edgesNodeSS = parseEdgesSelections(childField, edgesNodeSS, keys);
                wantsCursor = wantsCursor || keys.containsKey(FIELD_CURSOR);
            } else if (FIELD_TOTAL_COUNT.equals(fname)) {
                keys.put(FIELD_TOTAL_COUNT, responseKey(childField));
                wantsTotalCount = true;
            } else if (FIELD_PAGE_INFO.equals(fname)) {
                keys.put(FIELD_PAGE_INFO, responseKey(childField));
                wantsPageInfo = true;
                collectPageInfoFields(childField, pageInfoFields);
            } else {
                requireTypename(fname);
            }
        }
        return new ConnectionSelections(edgesNodeSS, wantsCursor, wantsTotalCount, wantsPageInfo, pageInfoFields, keys);
    }

    private SelectionSet parseEdgesSelections(Field edgesField, SelectionSet current, Map<String, String> keys) {
        if (edgesField.getSelectionSet() == null) return current;
        SelectionSet node = current;
        for (Selection<?> es : edgesField.getSelectionSet().getSelections()) {
            if (!(es instanceof Field ef)) continue;
            if (FIELD_NODE.equals(ef.getName())) {
                keys.put(FIELD_NODE, responseKey(ef));
                node = ef.getSelectionSet();
            } else if (FIELD_CURSOR.equals(ef.getName())) {
                keys.put(FIELD_CURSOR, responseKey(ef));
            } else {
                requireTypename(ef.getName());
            }
        }
        return node;
    }

    private void collectPageInfoFields(Field pageInfoField, Map<String, String> acc) {
        if (pageInfoField.getSelectionSet() == null) return;
        for (Selection<?> ps : pageInfoField.getSelectionSet().getSelections()) {
            if (ps instanceof Field pf) acc.put(responseKey(pf), pf.getName());
        }
    }

    private PaginationArgs parsePaginationArgs(Field field, String tableName) {
        int cap = filterBuilder.rowCap(tableName);
        Integer first = null;
        Integer last = null;
        String afterCursor = null;
        String beforeCursor = null;
        Map<String, Object> vars = FilterBuilder.boundVariables();
        for (Argument arg : field.getArguments()) {
            String argName = arg.getName();
            if (ARG_FIRST.equals(argName)) {
                Integer value = filterBuilder.nonNegative(arg);
                if (value != null) first = Math.min(value, cap);
            } else if (ARG_LAST.equals(argName)) {
                Integer value = filterBuilder.nonNegative(arg);
                if (value != null) last = Math.min(value, cap);
            } else if (ARG_AFTER.equals(argName)) {
                afterCursor = filterBuilder.resolveStringArg(arg.getValue(), vars);
            } else if (ARG_BEFORE.equals(argName)) {
                beforeCursor = filterBuilder.resolveStringArg(arg.getValue(), vars);
            }
        }
        return new PaginationArgs(first, last, afterCursor, beforeCursor);
    }

    /**
     * CTE 1: __records — the filtered rows past the cursors, in fetch order (reversed for {@code last}), limit+1
     * of them so the extra row tells whether another page follows.
     */
    private void appendRecordsCte(StringBuilder sql, Field field, ConnectionCtx ctx, Map<String, Object> params) {
        sql.append(ctx.recordsCte).append(AS_OPEN);
        sql.append(SELECT).append(ctx.block).append(".*");
        sql.append(FROM).append(qualifiedTable(ctx.tableName)).append(" ").append(ctx.block);

        List<String> conditions = new ArrayList<>();
        filterBuilder.buildWhereConditions(field, ctx.block, params, conditions, ctx.tableName);
        if (ctx.pagination.afterCursor != null) {
            conditions.add(keysetCondition(ctx, ctx.pagination.afterCursor, true, params));
        }
        if (ctx.pagination.beforeCursor != null) {
            conditions.add(keysetCondition(ctx, ctx.pagination.beforeCursor, false, params));
        }

        if (!conditions.isEmpty()) {
            sql.append(WHERE).append(String.join(AND, conditions));
        }
        sql.append(ORDER_BY).append(orderTerms(ctx.block, ctx.sortKeys, !ctx.isForward));

        String limitParam = namedParam(P_LIMIT, params.size());
        sql.append(LIMIT).append(PARAM_PREFIX).append(limitParam);
        params.put(limitParam, ctx.limit + 1);
        sql.append(")");
    }

    /** CTE 2: __page — the rows this page returns: the first {@code limit} of __records in fetch order. */
    private void appendPageCte(StringBuilder sql, ConnectionCtx ctx, Map<String, Object> params) {
        String alias = dialect.randAlias();
        String limitParam = namedParam(P_PAGE_LIMIT, params.size());
        params.put(limitParam, ctx.limit);
        sql.append(", ").append(ctx.pageCte).append(AS_OPEN).append(SELECT).append("*").append(FROM)
                .append(ctx.recordsCte).append(" ").append(alias)
                .append(ORDER_BY).append(orderTerms(alias, ctx.sortKeys, !ctx.isForward))
                .append(LIMIT).append(PARAM_PREFIX).append(limitParam).append(")");
    }

    /**
     * Rows strictly after ({@code after}) or before the cursor's position in the connection's order: the
     * row-value comparison {@code (k1, …, kn) > (v1, …, vn)} written out key by key, NULL-aware.
     */
    private String keysetCondition(ConnectionCtx ctx, String cursor, boolean after, Map<String, Object> params) {
        List<Object> values = decodeCursor(cursor, ctx.sortKeys.size());
        List<String> alternatives = new ArrayList<>();
        List<String> equalPrefix = new ArrayList<>();
        for (int i = 0; i < ctx.sortKeys.size(); i++) {
            SortKey key = ctx.sortKeys.get(i);
            String col = ctx.block + "." + dialect.quoteIdentifier(key.column());
            Object value = values.get(i);
            String beyond = beyond(col, after ? key : key.reversed(), value, ctx.tableName, params);
            if (beyond != null) {
                List<String> terms = new ArrayList<>(equalPrefix);
                terms.add(beyond);
                alternatives.add("(" + String.join(AND, terms) + ")");
            }
            equalPrefix.add(value == null ? col + IS_NULL
                    : col + " = " + filterBuilder.bindColumnValue(ctx.tableName, key.column(), value, P_AFTER, params));
        }
        return alternatives.isEmpty() ? "1 = 0" : "(" + String.join(" OR ", alternatives) + ")";
    }

    /** Rows whose {@code col} sorts strictly past {@code value} in {@code key}'s order; null when none can. */
    private String beyond(String col, SortKey key, Object value, String tableName, Map<String, Object> params) {
        if (value == null) {
            return key.nullsLast() ? null : col + IS_NOT_NULL;
        }
        String bound = filterBuilder.bindColumnValue(tableName, key.column(), value, P_AFTER, params);
        String past = col + (key.descending() ? " < " : " > ") + bound;
        return key.nullsLast() ? "(" + past + " OR " + col + IS_NULL + ")" : past;
    }

    private void appendHasNextCte(StringBuilder sql, String block, String recordsCte, int limit,
                                  Map<String, Object> params) {
        String hnParam = namedParam(P_HN_LIMIT, params.size());
        sql.append(", ").append(dialect.cteName(block, CTE_HAS_NEXT)).append(AS_OPEN);
        sql.append(SELECT).append(COUNT_ALL).append(" > :").append(hnParam);
        params.put(hnParam, limit);
        sql.append(" AS val").append(FROM).append(recordsCte);
        sql.append(")");
    }

    private void appendHasPrevCte(StringBuilder sql, String block, String afterCursor, boolean isForward) {
        sql.append(", ").append(dialect.cteName(block, "_has_prev")).append(AS_OPEN);
        if (afterCursor != null || !isForward) {
            sql.append(SELECT).append("true AS val");
        } else {
            sql.append(SELECT).append("false AS val");
        }
        sql.append(")");
    }

    private void appendTotalCountCte(StringBuilder sql, Field field, String tableName, String block,
                                     Map<String, Object> params) {
        String countBlock = dialect.randAlias();
        sql.append(", ").append(dialect.cteName(block, "_total")).append(AS_OPEN);
        sql.append(SELECT).append(COUNT_ALL).append(" AS val").append(FROM).append(qualifiedTable(tableName)).append(" ").append(countBlock);
        List<String> countConds = new ArrayList<>();
        filterBuilder.buildWhereConditions(field, countBlock, params, countConds, tableName);
        if (!countConds.isEmpty()) {
            sql.append(WHERE).append(String.join(AND, countConds));
        }
        sql.append(")");
    }

    /** The page's edges in the connection's order (a {@code last} page is fetched reversed, so it is turned back). */
    private String buildEdgesSubquery(ConnectionCtx ctx, ConnectionSelections sel, Map<String, Object> params) {
        String pageBlock = dialect.randAlias();
        List<String> edgeParts = new ArrayList<>();
        if (sel.wantsCursor) {
            edgeParts.add(jsonKey(sel.keys.get(FIELD_CURSOR)) + ", " + cursorExpr(pageBlock, ctx.sortKeys));
        }
        if (sel.keys.containsKey(FIELD_NODE)) {
            edgeParts.add(jsonKey(sel.keys.get(FIELD_NODE)) + ", "
                    + buildObject(sel.edgesNodeSS, ctx.tableName, pageBlock, params));
        }
        String edgeObj = dialect.buildObject(edgeParts);

        // The LIMIT keeps the derived table's ORDER BY: MySQL drops an ORDER BY a derived table has without one.
        String ordered = dialect.randAlias();
        String limitParam = namedParam(P_PAGE_LIMIT, params.size());
        params.put(limitParam, ctx.limit);
        return SELECT + dialect.aggregateArray(edgeObj) + FROM + "(" + SELECT + "*" + FROM + ctx.pageCte + " " + ordered
                + ORDER_BY + orderTerms(ordered, ctx.sortKeys, false) + LIMIT + PARAM_PREFIX + limitParam + ") "
                + pageBlock;
    }

    /**
     * {@code pageInfoFields} maps each requested field's response key to its name; none requested means
     * both page flags under their own names.
     */
    private String buildPageInfoObject(ConnectionCtx ctx, Map<String, String> pageInfoFields,
                                       boolean needsHasNext, boolean needsHasPrev) {
        Map<String, String> requested = pageInfoFields.isEmpty()
                ? Map.of(FIELD_HAS_NEXT_PAGE, FIELD_HAS_NEXT_PAGE, FIELD_HAS_PREVIOUS_PAGE, FIELD_HAS_PREVIOUS_PAGE)
                : pageInfoFields;
        List<String> piParts = new ArrayList<>();
        requested.forEach((key, name) -> {
            String value = pageInfoValue(ctx, name, needsHasNext, needsHasPrev);
            if (value != null) {
                piParts.add(jsonKey(key) + ", " + value);
            } else {
                requireTypename(name);
            }
        });
        return dialect.buildObject(piParts);
    }

    private String pageInfoValue(ConnectionCtx ctx, String name, boolean needsHasNext, boolean needsHasPrev) {
        if (FIELD_HAS_NEXT_PAGE.equals(name) && needsHasNext) {
            return ctx.isForward
                    ? dialect.jsonBool("(" + SELECT + "val" + FROM + dialect.cteName(ctx.block, CTE_HAS_NEXT) + ")")
                    : dialect.jsonBoolLiteral(ctx.pagination.beforeCursor != null);
        }
        if (FIELD_HAS_PREVIOUS_PAGE.equals(name) && needsHasPrev) {
            String cte = ctx.isForward ? "_has_prev" : CTE_HAS_NEXT;
            return dialect.jsonBool("(" + SELECT + "val" + FROM + dialect.cteName(ctx.block, cte) + ")");
        }
        if (FIELD_START_CURSOR.equals(name) || FIELD_END_CURSOR.equals(name)) {
            // The first or last edge returned, in the connection's order; null on an empty page.
            String alias = dialect.randAlias();
            return "(" + SELECT + cursorExpr(alias, ctx.sortKeys) + FROM + ctx.pageCte + " " + alias
                    + ORDER_BY + orderTerms(alias, ctx.sortKeys, FIELD_END_CURSOR.equals(name)) + LIMIT + "1)";
        }
        return null;
    }

    // === JSON object builder (recursive with FK traversal) ===

    /**
     * Threads the query's bind-param map so nested FK sub-selects splice their
     * own RLS predicate; reads and mutation results must both pass the live map.
     */
    public String buildObject(SelectionSet selectionSet, String tableName, String alias, Map<String, Object> params) {
        if (selectionSet == null) return dialect.buildObject(List.of());

        List<String> pairs = new ArrayList<>();
        Set<String> columns = schemaInfo.getColumns(tableName);

        for (Field field : flattenSelections(selectionSet, fragmentsHolder.get())) {
            if (TYPENAME.equals(field.getName())) continue;
            String pair = buildFieldPair(field, tableName, alias, columns, params);
            if (pair == null) {
                throw unknownField(field.getName());
            }
            pairs.add(pair);
        }

        return dialect.buildObject(pairs);
    }

    /**
     * {@code { affected_rows }} over the rows a write returned, for a caller who may not read them; an
     * aggregate, so the statement answers one object however many rows were written.
     */
    public String buildAffectedRows(SelectionSet selectionSet) {
        return buildAffectedRows(selectionSet, "count(*)");
    }

    /** As above, with the count given by {@code countSql}, e.g. a nested insert's rows over several statements. */
    public String buildAffectedRows(SelectionSet selectionSet, String countSql) {
        List<String> pairs = new ArrayList<>();
        for (Field field : flattenSelections(selectionSet, fragmentsHolder.get())) {
            if (FIELD_AFFECTED_ROWS.equals(field.getName())) {
                pairs.add(jsonKey(field) + ", " + countSql);
            } else {
                requireTypename(field.getName());
            }
        }
        return dialect.buildObject(pairs);
    }

    /** Resolve a single selection field into its JSON pair, or null if not recognized. */
    private String buildFieldPair(Field field, String tableName, String alias, Set<String> columns,
                                  Map<String, Object> params) {
        String name = field.getName();
        String key = jsonKey(field);

        if (columns.contains(name)) {
            return key + ", " + buildColumnValue(field, tableName, alias, name);
        }

        SchemaInfo.FkInfo fk = schemaInfo.getForwardFk(tableName, name);
        if (fk != null) {
            return key + ", " + buildForwardFkValue(field, alias, fk, params);
        }

        SchemaInfo.ReverseFkInfo rfk = schemaInfo.getReverseFk(tableName, name);
        if (rfk != null) {
            return key + ", " + buildReverseFkValue(field, alias, rfk, params);
        }

        if (name.endsWith(AGGREGATE_SUFFIX)) {
            SchemaInfo.ReverseFkInfo aggregated = schemaInfo.getReverseFk(tableName,
                    name.substring(0, name.length() - AGGREGATE_SUFFIX.length()));
            if (aggregated != null) {
                return key + ", " + buildReverseFkAggregate(field, alias, aggregated, params);
            }
        }

        // A computed field is a function nobody tracks: reflected, never served.
        return null;
    }

    private String buildColumnValue(Field field, String tableName, String alias, String name) {
        // Check if this is a composite type column with a selection set
        String udtName = schemaInfo.getEnumType(tableName, name);
        if (udtName != null && schemaInfo.isCompositeType(udtName) && field.getSelectionSet() != null) {
            // Decompose composite type into jsonb_build_object with selected sub-fields
            String colRef = alias + "." + dialect.quoteIdentifier(name);
            List<String> subParts = flattenSelections(field.getSelectionSet(), fragmentsHolder.get()).stream()
                    .map(subField -> jsonKey(subField) + ", (" + colRef + ")." + dialect.quoteIdentifier(subField.getName()))
                    .toList();
            return dialect.buildObject(subParts);
        }

        // Scalar column
        String colRef = alias + "." + dialect.quoteIdentifier(name);
        colRef = dialect.selectColumn(colRef, schemaInfo.getColumnType(tableName, name));
        // Enum columns: uppercase for GraphQL convention
        String enumType = schemaInfo.getEnumType(tableName, name);
        if (enumType != null && schemaInfo.getEnumTypes().containsKey(enumType)) {
            colRef = dialect.enumToText(colRef);
        }
        return colRef;
    }

    private String buildForwardFkValue(Field field, String alias, SchemaInfo.FkInfo fk,
                                       Map<String, Object> params) {
        String subAlias = dialect.randAlias();
        String subObj = buildObject(field.getSelectionSet(), fk.refTable(), subAlias, params);
        // Build multi-column WHERE join for composite FKs
        List<String> joinConds = new ArrayList<>();
        for (int i = 0; i < fk.fkColumns().size(); i++) {
            joinConds.add(subAlias + "." + dialect.quoteIdentifier(fk.refColumns().get(i))
                    + " = " + alias + "." + dialect.quoteIdentifier(fk.fkColumns().get(i)));
        }
        appendNestedRls(joinConds, fk.refTable(), subAlias, params);
        return "(" + SELECT + subObj
                + FROM + qualifiedTable(fk.refTable()) + " " + subAlias
                + WHERE + String.join(AND, joinConds) + ")";
    }

    /**
     * A nested list: the child rows joined to this row, narrowed by the field's {@code where} and the
     * role's select filter, in its {@code orderBy}, and paged by its {@code limit}/{@code offset} within
     * the role's own row limit on the child table.
     */
    private String buildReverseFkValue(Field field, String alias, SchemaInfo.ReverseFkInfo rfk,
                                       Map<String, Object> params) {
        FieldArguments.requireKnown(field, FieldArguments.NESTED_LIST);
        String childTable = rfk.childTable();
        String subAlias = dialect.randAlias();
        String subObj = buildObject(field.getSelectionSet(), childTable, subAlias, params);
        // Build multi-column WHERE join for composite FKs
        List<String> conditions = new ArrayList<>();
        for (int i = 0; i < rfk.fkColumns().size(); i++) {
            conditions.add(subAlias + "." + dialect.quoteIdentifier(rfk.fkColumns().get(i))
                    + " = " + alias + "." + dialect.quoteIdentifier(rfk.refColumns().get(i)));
        }
        filterBuilder.buildWhereConditions(field, subAlias, params, conditions, childTable);
        StringBuilder rows = new StringBuilder(SELECT).append(subAlias).append(".*")
                .append(FROM).append(qualifiedTable(childTable)).append(" ").append(subAlias)
                .append(WHERE).append(String.join(AND, conditions));
        filterBuilder.applyOrderBy(rows, field, subAlias, childTable);
        filterBuilder.applyNestedLimit(rows, field, params, childTable);
        return "(" + SELECT + dialect.coalesceArray(dialect.aggregateArray(subObj))
                + FROM + "(" + rows + ") " + subAlias + ")";
    }

    /**
     * A nested aggregate ({@code publicTasksAggregate}): the aggregate of the child rows joined to this row,
     * narrowed by the field's {@code where} and the role's select filter, as Hasura's array-relationship
     * aggregate. It exists only where the role is allowed aggregations on the child table.
     */
    private String buildReverseFkAggregate(Field field, String alias, SchemaInfo.ReverseFkInfo rfk,
                                           Map<String, Object> params) {
        FieldArguments.requireKnown(field, FieldArguments.AGGREGATE);
        requireAggregations(field.getName(), rfk.childTable());
        return "(" + SELECT + aggregateObject(field, rfk.childTable(), subAlias -> {
            List<String> join = new ArrayList<>();
            for (int i = 0; i < rfk.fkColumns().size(); i++) {
                join.add(subAlias + "." + dialect.quoteIdentifier(rfk.fkColumns().get(i))
                        + " = " + alias + "." + dialect.quoteIdentifier(rfk.refColumns().get(i)));
            }
            return join;
        }, params) + ")";
    }

    /**
     * Splices the related table's RLS predicate into a nested FK sub-select's
     * WHERE, so embedded relations are filtered on the same footing as top-level
     * tables (EXC-315 multi-table threading). {@code subAlias} is the sub-select's
     * own alias for the related table — passing it lets relationship/EXISTS
     * predicates correlate back to the aliased table (a bare table-name reference
     * would fail with <em>missing FROM-clause entry</em> under embed aliasing) and
     * qualifies scalar rules to the same alias. No-op without a registered contributor.
     */
    private void appendNestedRls(List<String> joinConds, String relatedTable, String subAlias,
                                 Map<String, Object> params) {
        filterBuilder.appendRlsConditions(joinConds, relatedTable, subAlias, params, RlsOp.SELECT);
    }

    /** The key a field answers under: its alias when it has one, else its name. */
    static String responseKey(Field field) {
        return field.getAlias() != null ? field.getAlias() : field.getName();
    }

    /** A field's response key as the SQL string literal a JSON object is built with. */
    static String jsonKey(Field field) {
        return jsonKey(responseKey(field));
    }

    /** {@code key} as a SQL string literal; a GraphQL name never holds a quote, anything else is refused. */
    static String jsonKey(String key) {
        if (key == null || !GRAPHQL_NAME.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid field name: " + key);
        }
        return "'" + key + "'";
    }

    /** {@code __typename} is the one field every type answers; anything else unknown fails the request. */
    private static void requireTypename(String fieldName) {
        if (!TYPENAME.equals(fieldName)) {
            throw unknownField(fieldName);
        }
    }

    static IllegalArgumentException unknownField(String fieldName) {
        return new IllegalArgumentException("Unknown field(s): " + fieldName);
    }

    // === Fragment expansion ===

    public List<Field> flattenSelections(SelectionSet selectionSet, Map<String, FragmentDefinition> fragments) {
        if (selectionSet == null) return List.of();
        List<Field> fields = new ArrayList<>();
        for (Selection<?> sel : selectionSet.getSelections()) {
            if (sel instanceof Field f) {
                fields.add(f);
            } else if (sel instanceof FragmentSpread spread && fragments != null) {
                FragmentDefinition frag = fragments.get(spread.getName());
                if (frag != null) {
                    fields.addAll(flattenSelections(frag.getSelectionSet(), fragments));
                }
            } else if (sel instanceof InlineFragment inl) {
                fields.addAll(flattenSelections(inl.getSelectionSet(), fragments));
            }
        }
        return fields;
    }

    // === Table name resolution ===

    public String resolveTableName(String fieldName) {
        String name = fieldName;
        for (String suffix : List.of(CONNECTION_SUFFIX, AGGREGATE_SUFFIX)) {
            if (name.endsWith(suffix)) {
                name = name.substring(0, name.length() - suffix.length());
                break;
            }
        }
        // camelCase → snake_case
        String snake = NamingUtils.camelToSnakeCase(name);
        if (schemaInfo.hasTable(snake)) return snake;
        if (schemaInfo.hasTable(name)) return name;

        // Compound keys: match prefixed field name (e.g., "publicUsers" → "public.users")
        for (String table : schemaInfo.getTableNames()) {
            if (!table.contains(".")) continue;
            String schema = table.substring(0, table.indexOf('.'));
            String rawTable = table.substring(table.indexOf('.') + 1);
            String expectedField = NamingUtils.schemaFieldName(schema, rawTable);
            if (expectedField.equals(name)) return table;
        }

        return null;
    }

    // === Internal helpers ===

    private static final ObjectMapper CURSOR_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    /**
     * A cursor's sort-key values: a JSON array, one per key. A cursor of the earlier form (the primary key
     * alone) still names a row of a connection ordered by its key only.
     */
    List<Object> decodeCursor(String cursor, int keyCount) {
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(cursor.trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException _) {
            throw invalidCursor(cursor);
        }
        if (!decoded.startsWith("[")) {
            if (keyCount != 1) throw invalidCursor(cursor);
            return List.of(legacyKey(decoded));
        }
        try {
            List<Object> values = CURSOR_MAPPER.readValue(decoded, new TypeReference<List<Object>>() {});
            if (values.size() != keyCount) throw invalidCursor(cursor);
            return values;
        } catch (JsonProcessingException _) {
            throw invalidCursor(cursor);
        }
    }

    private static Object legacyKey(String decoded) {
        try {
            return Long.parseLong(decoded);
        } catch (NumberFormatException _) {
            return decoded;
        }
    }

    private static IllegalArgumentException invalidCursor(String cursor) {
        return new IllegalArgumentException("Invalid cursor '" + cursor + "': it is not a cursor of this connection"
                + " and order");
    }

    private String getPk(String tableName) {
        return schemaInfo.getPrimaryKey(tableName);
    }
}
