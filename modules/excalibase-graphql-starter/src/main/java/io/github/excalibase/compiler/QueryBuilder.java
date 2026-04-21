package io.github.excalibase.compiler;

import graphql.language.*;
import io.github.excalibase.schema.NamingUtils;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.SqlDialect;

import java.util.*;
import static io.github.excalibase.schema.GraphqlConstants.*;
import static io.github.excalibase.compiler.SqlKeywords.*;

/**
 * Handles query compilation: list, connection (CTE-based cursor pagination),
 * aggregate, and recursive JSON object building with FK traversal.
 * Extracted from SqlCompiler to separate query logic from parsing/routing.
 */
public class QueryBuilder {

    private static final String CTE_HAS_NEXT = "_has_next";

    private final SchemaInfo schemaInfo;
    private final SqlDialect dialect;
    private final FilterBuilder filterBuilder;
    private final VectorSearchBuilder vectorSearchBuilder;
    private final String dbSchema;
    private final int maxRows;
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
        this.maxRows = maxRows;
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
        String alias = dialect.randAlias();
        String objectSql = buildObject(field.getSelectionSet(), tableName, alias);

        // Parse distinctOn argument
        List<String> distinctOnCols = parseDistinctOn(field);

        // Parse vector argument (k-NN search). When present, it takes precedence
        // over user-supplied orderBy and limit — the embedding similarity order
        // IS the sort. Absent/invalid input returns Optional.empty() and we fall
        // through to the normal ORDER BY / LIMIT path.
        Optional<VectorSearchBuilder.VectorClause> vectorClause = extractVectorClause(field, alias, params);

        StringBuilder sql = new StringBuilder();
        sql.append(SELECT).append(dialect.coalesceArray(dialect.aggregateArray(objectSql)));
        sql.append(FROM).append("(").append(SELECT);

        if (!distinctOnCols.isEmpty()) {
            sql.append(dialect.distinctOn(distinctOnCols, alias)).append(" ");
        }

        sql.append(alias).append(".*");
        sql.append(FROM).append(qualifiedTable(tableName)).append(" ").append(alias);

        // WHERE from arguments
        filterBuilder.applyWhere(sql, field, alias, params, tableName);

        appendListOrderBy(sql, field, alias, distinctOnCols, vectorClause);
        appendListLimit(sql, field, params, vectorClause);

        sql.append(") ").append(alias);
        return sql.toString();
    }

    /** ORDER BY precedence: vector > distinctOn > user-supplied orderBy. */
    private void appendListOrderBy(StringBuilder sql, Field field, String alias,
                                   List<String> distinctOnCols,
                                   Optional<VectorSearchBuilder.VectorClause> vectorClause) {
        if (vectorClause.isPresent()) {
            sql.append(ORDER_BY).append(vectorClause.get().orderByFragment());
        } else if (!distinctOnCols.isEmpty()) {
            sql.append(ORDER_BY).append(joinCols(buildDistinctOnOrderClauses(field, alias, distinctOnCols)));
        } else {
            filterBuilder.applyOrderBy(sql, field, alias);
        }
    }

    /** DISTINCT ON requires distinct columns first; then append any user ORDER BY columns not already covered. */
    private List<String> buildDistinctOnOrderClauses(Field field, String alias, List<String> distinctOnCols) {
        List<String> orderClauses = new ArrayList<>();
        for (String col : distinctOnCols) {
            orderClauses.add(alias + "." + dialect.quoteIdentifier(col) + " " + ASC);
        }
        Argument orderByArg = field.getArguments().stream()
                .filter(a -> ARG_ORDER_BY.equals(a.getName()))
                .findFirst().orElse(null);
        if (orderByArg != null && orderByArg.getValue() instanceof ObjectValue ov) {
            for (ObjectField of : ov.getObjectFields()) {
                if (!distinctOnCols.contains(of.getName())) {
                    String dir = of.getValue() instanceof EnumValue ev ? ev.getName() : ASC;
                    orderClauses.add(alias + "." + dialect.quoteIdentifier(of.getName()) + " " + dir);
                }
            }
        }
        return orderClauses;
    }

    /** LIMIT precedence: vector.limitOverride > user/argument limit. */
    private void appendListLimit(StringBuilder sql, Field field, Map<String, Object> params,
                                 Optional<VectorSearchBuilder.VectorClause> vectorClause) {
        if (vectorClause.isPresent() && vectorClause.get().limitOverride() != null) {
            int vlimit = Math.min(vectorClause.get().limitOverride(), maxRows);
            String paramName = "p_limit_" + params.size();
            sql.append(LIMIT).append(":").append(paramName);
            params.put(paramName, vlimit);
        } else {
            filterBuilder.applyLimit(sql, field, params);
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
            Field field, String alias, Map<String, Object> params) {
        Argument vectorArg = field.getArguments().stream()
                .filter(a -> ARG_VECTOR.equals(a.getName()))
                .findFirst().orElse(null);
        if (vectorArg == null || !(vectorArg.getValue() instanceof ObjectValue ov)) {
            return Optional.empty();
        }
        return vectorSearchBuilder.build(ov, alias, schemaInfo, params);
    }

    private List<String> parseDistinctOn(Field field) {
        List<String> cols = new ArrayList<>();
        for (Argument arg : field.getArguments()) {
            if (ARG_DISTINCT_ON.equals(arg.getName()) && arg.getValue() instanceof ArrayValue av) {
                for (Value<?> v : av.getValues()) {
                    if (v instanceof StringValue sv) {
                        cols.add(sv.getValue());
                    }
                }
            }
        }
        return cols;
    }

    // === Aggregates ===

    public String compileAggregate(Field field, String tableName, Map<String, Object> params) {
        String alias = dialect.randAlias();
        List<String> parts = new ArrayList<>();

        for (Selection<?> sel : field.getSelectionSet().getSelections()) {
            if (!(sel instanceof Field aggField)) continue;
            String aggName = aggField.getName();

            if (FIELD_COUNT.equals(aggName)) {
                StringBuilder countSql = new StringBuilder();
                countSql.append(SELECT).append(COUNT_ALL).append(FROM).append(qualifiedTable(tableName)).append(" ").append(alias);
                filterBuilder.applyWhere(countSql, field, alias, params, tableName);
                parts.add("'count', (" + countSql + ")");
            } else if (Set.of(AGG_SUM, AGG_AVG, AGG_MIN, AGG_MAX).contains(aggName) && aggField.getSelectionSet() != null) {
                // Nested per-column aggregate -- sum(total_amount), avg(total_amount), etc.
                List<String> colParts = new ArrayList<>();
                for (Selection<?> colSel : aggField.getSelectionSet().getSelections()) {
                    if (colSel instanceof Field colField) {
                        String col = colField.getName();
                        String subAlias = dialect.randAlias();
                        StringBuilder subSql = new StringBuilder();
                        subSql.append(SELECT).append(aggName).append("(").append(subAlias).append(".").append(dialect.quoteIdentifier(col)).append(")").append(FROM)
                                .append(qualifiedTable(tableName)).append(" ").append(subAlias);
                        filterBuilder.applyWhere(subSql, field, subAlias, params, tableName);
                        colParts.add("'" + col + "', (" + subSql + ")");
                    }
                }
                parts.add("'" + aggName + "', " + dialect.buildObject(colParts));
            }
        }

        return SELECT + dialect.buildObject(parts);
    }

    // === Connection (CTE-based cursor pagination) ===

    public String compileConnection(Field field, String tableName, Map<String, Object> params) {
        String block = dialect.randAlias();
        String pk = getPk(tableName);

        ConnectionSelections sel = parseConnectionSelections(field);
        PaginationArgs pagination = parsePaginationArgs(field);

        // Determine order columns (default: PK ASC)
        List<String[]> orderCols = filterBuilder.parseOrderBy(field);
        if (orderCols.isEmpty()) orderCols.add(new String[]{pk, ASC});

        boolean isForward = (pagination.last == null);
        int forwardLimit = (pagination.first != null) ? pagination.first : maxRows;
        int limit = isForward ? forwardLimit : pagination.last;

        // === Build CTE-based Connection SQL ===
        StringBuilder sql = new StringBuilder();
        sql.append(WITH);

        String recordsCte = dialect.cteName(block, "_records");
        ConnectionCtx ctx = new ConnectionCtx(block, pk, recordsCte, orderCols, isForward, limit, pagination);
        appendRecordsCte(sql, field, tableName, ctx, params);

        boolean needsHasNext = sel.wantsPageInfo
                && (sel.pageInfoFields.isEmpty() || sel.pageInfoFields.contains(FIELD_HAS_NEXT_PAGE));
        boolean needsHasPrev = sel.wantsPageInfo
                && (sel.pageInfoFields.isEmpty() || sel.pageInfoFields.contains(FIELD_HAS_PREVIOUS_PAGE));

        if (needsHasNext) {
            appendHasNextCte(sql, block, recordsCte, limit, params);
        }
        if (needsHasPrev) {
            appendHasPrevCte(sql, block, pagination.afterCursor, isForward);
        }
        if (sel.wantsTotalCount) {
            appendTotalCountCte(sql, field, tableName, block, params);
        }

        // === Final SELECT from CTEs ===
        String pageBlock = dialect.randAlias();
        String edgesSub = buildEdgesSubquery(tableName, pk, pageBlock, recordsCte, sel, limit, params);

        List<String> rootParts = new ArrayList<>();
        rootParts.add("'edges', " + dialect.coalesceArray("(" + edgesSub + ")"));
        if (sel.wantsTotalCount) {
            rootParts.add("'totalCount', (" + SELECT + "val" + FROM + dialect.cteName(block, "_total") + ")");
        }
        if (sel.wantsPageInfo) {
            rootParts.add("'pageInfo', " + buildPageInfoObject(ctx, sel.pageInfoFields,
                    needsHasNext, needsHasPrev));
        }

        sql.append(" ").append(SELECT).append(dialect.buildObject(rootParts));
        return sql.toString();
    }

    /** Bag of booleans/sets describing which connection sub-fields the caller requested. */
    private record ConnectionSelections(SelectionSet edgesNodeSS, boolean wantsCursor,
                                        boolean wantsTotalCount, boolean wantsPageInfo,
                                        Set<String> pageInfoFields) {}

    /** Pagination argument values extracted from the Field. */
    private record PaginationArgs(Integer first, Integer last, String afterCursor, String beforeCursor) {}

    /** Shared connection-scoped state passed to the per-CTE helpers. */
    private record ConnectionCtx(String block, String pk, String recordsCte,
                                 List<String[]> orderCols, boolean isForward, int limit,
                                 PaginationArgs pagination) {}

    private ConnectionSelections parseConnectionSelections(Field field) {
        SelectionSet edgesNodeSS = null;
        boolean wantsCursor = false;
        boolean wantsTotalCount = false;
        boolean wantsPageInfo = false;
        Set<String> pageInfoFields = new HashSet<>();

        for (Selection<?> selection : field.getSelectionSet().getSelections()) {
            if (!(selection instanceof Field childField)) continue;
            String fname = childField.getName();
            if (FIELD_EDGES.equals(fname)) {
                edgesNodeSS = parseEdgesSelections(childField, edgesNodeSS);
                wantsCursor = wantsCursor || edgeWantsCursor(childField);
            } else if (FIELD_TOTAL_COUNT.equals(fname)) {
                wantsTotalCount = true;
            } else if (FIELD_PAGE_INFO.equals(fname)) {
                wantsPageInfo = true;
                collectPageInfoFields(childField, pageInfoFields);
            }
        }
        return new ConnectionSelections(edgesNodeSS, wantsCursor, wantsTotalCount, wantsPageInfo, pageInfoFields);
    }

    private SelectionSet parseEdgesSelections(Field edgesField, SelectionSet current) {
        if (edgesField.getSelectionSet() == null) return current;
        for (Selection<?> es : edgesField.getSelectionSet().getSelections()) {
            if (es instanceof Field ef && FIELD_NODE.equals(ef.getName())) {
                return ef.getSelectionSet();
            }
        }
        return current;
    }

    private boolean edgeWantsCursor(Field edgesField) {
        if (edgesField.getSelectionSet() == null) return false;
        for (Selection<?> es : edgesField.getSelectionSet().getSelections()) {
            if (es instanceof Field ef && FIELD_CURSOR.equals(ef.getName())) return true;
        }
        return false;
    }

    private void collectPageInfoFields(Field pageInfoField, Set<String> acc) {
        if (pageInfoField.getSelectionSet() == null) return;
        for (Selection<?> ps : pageInfoField.getSelectionSet().getSelections()) {
            if (ps instanceof Field pf) acc.add(pf.getName());
        }
    }

    private PaginationArgs parsePaginationArgs(Field field) {
        Integer first = null;
        Integer last = null;
        String afterCursor = null;
        String beforeCursor = null;
        Map<String, Object> vars = FilterBuilder.boundVariables();
        for (Argument arg : field.getArguments()) {
            String argName = arg.getName();
            if (ARG_FIRST.equals(argName)) {
                Integer value = filterBuilder.resolveIntArg(arg.getValue(), vars);
                if (value != null) first = Math.min(value, maxRows);
            } else if (ARG_LAST.equals(argName)) {
                Integer value = filterBuilder.resolveIntArg(arg.getValue(), vars);
                if (value != null) last = Math.min(value, maxRows);
            } else if (ARG_AFTER.equals(argName)) {
                afterCursor = filterBuilder.resolveStringArg(arg.getValue(), vars);
            } else if (ARG_BEFORE.equals(argName)) {
                beforeCursor = filterBuilder.resolveStringArg(arg.getValue(), vars);
            }
        }
        return new PaginationArgs(first, last, afterCursor, beforeCursor);
    }

    /** CTE 1: __records — filtered, ordered, limited rows (fetches limit+1 for has-next detection). */
    private void appendRecordsCte(StringBuilder sql, Field field, String tableName,
                                  ConnectionCtx ctx, Map<String, Object> params) {
        sql.append(ctx.recordsCte).append(AS_OPEN);
        sql.append(SELECT).append(ctx.block).append(".*");
        sql.append(FROM).append(qualifiedTable(tableName)).append(" ").append(ctx.block);

        List<String> conditions = new ArrayList<>();
        filterBuilder.buildWhereConditions(field, ctx.block, params, conditions, tableName);
        appendCursorCondition(conditions, ctx.pagination.afterCursor, ctx.block, ctx.pk, P_AFTER, " > ", params);
        appendCursorCondition(conditions, ctx.pagination.beforeCursor, ctx.block, ctx.pk, P_BEFORE, " < ", params);

        if (!conditions.isEmpty()) {
            sql.append(WHERE).append(String.join(AND, conditions));
        }

        sql.append(ORDER_BY);
        List<String> orderClauses = new ArrayList<>();
        for (String[] oc : ctx.orderCols) {
            String dir = ctx.isForward ? oc[1] : reverseDir(oc[1]);
            orderClauses.add(ctx.block + "." + dialect.quoteIdentifier(oc[0]) + " " + dir);
        }
        sql.append(joinCols(orderClauses));

        String limitParam = namedParam(P_LIMIT, params.size());
        sql.append(LIMIT).append(PARAM_PREFIX).append(limitParam);
        params.put(limitParam, ctx.limit + 1);
        sql.append(")");
    }

    private void appendCursorCondition(List<String> conditions, String cursor, String block, String pk,
                                       String paramKey, String sqlOp, Map<String, Object> params) {
        if (cursor == null) return;
        String paramName = namedParam(paramKey, params.size());
        conditions.add(block + "." + dialect.quoteIdentifier(pk) + sqlOp + ":" + paramName);
        params.put(paramName, decodeCursor(cursor));
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

    private String buildEdgesSubquery(String tableName, String pk, String pageBlock, String recordsCte,
                                      ConnectionSelections sel, int limit, Map<String, Object> params) {
        List<String> edgeParts = new ArrayList<>();
        if (sel.wantsCursor) {
            edgeParts.add("'cursor', " + dialect.encodeCursor(pageBlock + "." + dialect.quoteIdentifier(pk)));
        }
        String nodeObj = sel.edgesNodeSS != null
                ? buildObject(sel.edgesNodeSS, tableName, pageBlock)
                : dialect.buildObject(List.of());
        edgeParts.add("'node', " + nodeObj);
        String edgeObj = dialect.buildObject(edgeParts);

        StringBuilder edgesSub = new StringBuilder();
        edgesSub.append(SELECT).append(dialect.aggregateArray(edgeObj));
        edgesSub.append(FROM).append("(").append(SELECT).append("*").append(FROM).append(recordsCte);
        String pageLimitParam = namedParam(P_PAGE_LIMIT, params.size());
        edgesSub.append(LIMIT).append(PARAM_PREFIX).append(pageLimitParam);
        params.put(pageLimitParam, limit);
        edgesSub.append(") ").append(pageBlock);
        return edgesSub.toString();
    }

    private String buildPageInfoObject(ConnectionCtx ctx, Set<String> pageInfoFields,
                                       boolean needsHasNext, boolean needsHasPrev) {
        List<String> piParts = new ArrayList<>();
        if (needsHasNext) {
            if (ctx.isForward) {
                piParts.add("'hasNextPage', " + dialect.jsonBool("(" + SELECT + "val" + FROM + dialect.cteName(ctx.block, CTE_HAS_NEXT) + ")"));
            } else {
                piParts.add("'hasNextPage', " + dialect.jsonBoolLiteral(ctx.pagination.beforeCursor != null));
            }
        }
        if (needsHasPrev) {
            String cte = ctx.isForward ? "_has_prev" : CTE_HAS_NEXT;
            piParts.add("'hasPreviousPage', " + dialect.jsonBool("(" + SELECT + "val" + FROM + dialect.cteName(ctx.block, cte) + ")"));
        }
        if (pageInfoFields.contains(FIELD_START_CURSOR)) {
            String pkTextExpr = "(" + SELECT + "CAST(" + dialect.quoteIdentifier(ctx.pk) + " AS CHAR)" + FROM + ctx.recordsCte + LIMIT + "1)";
            piParts.add("'startCursor', (" + SELECT + dialect.encodeCursor(pkTextExpr) + ")");
        }
        if (pageInfoFields.contains(FIELD_END_CURSOR)) {
            String pkTextExpr = "(" + SELECT + "CAST(" + dialect.quoteIdentifier(ctx.pk) + " AS CHAR)" + FROM + ctx.recordsCte +
                    ORDER_BY + dialect.quoteIdentifier(ctx.pk) + " " + DESC + LIMIT + "1)";
            piParts.add("'endCursor', (" + SELECT + dialect.encodeCursor(pkTextExpr) + ")");
        }
        return dialect.buildObject(piParts);
    }

    // === JSON object builder (recursive with FK traversal) ===

    public String buildObject(SelectionSet selectionSet, String tableName, String alias) {
        if (selectionSet == null) return dialect.buildObject(List.of());

        List<String> pairs = new ArrayList<>();
        Set<String> columns = schemaInfo.getColumns(tableName);

        for (Field field : flattenSelections(selectionSet, fragmentsHolder.get())) {
            String pair = buildFieldPair(field, tableName, alias, columns);
            if (pair != null) pairs.add(pair);
        }

        return dialect.buildObject(pairs);
    }

    /** Resolve a single selection field into its JSON pair, or null if not recognized. */
    private String buildFieldPair(Field field, String tableName, String alias, Set<String> columns) {
        String name = field.getName();

        if (columns.contains(name)) {
            return buildColumnPair(field, tableName, alias, name);
        }

        SchemaInfo.FkInfo fk = schemaInfo.getForwardFk(tableName, name);
        if (fk != null) {
            return buildForwardFkPair(field, alias, name, fk);
        }

        SchemaInfo.ReverseFkInfo rfk = schemaInfo.getReverseFk(tableName, name);
        if (rfk != null) {
            return buildReverseFkPair(field, alias, name, rfk);
        }

        return buildComputedFieldPair(tableName, alias, name);
    }

    private String buildColumnPair(Field field, String tableName, String alias, String name) {
        // Check if this is a composite type column with a selection set
        String udtName = schemaInfo.getEnumType(tableName, name);
        if (udtName != null && schemaInfo.isCompositeType(udtName) && field.getSelectionSet() != null) {
            // Decompose composite type into jsonb_build_object with selected sub-fields
            String colRef = alias + "." + dialect.quoteIdentifier(name);
            List<String> subParts = new ArrayList<>();
            for (Field subField : flattenSelections(field.getSelectionSet(), fragmentsHolder.get())) {
                String subName = subField.getName();
                subParts.add("'" + subName + "', (" + colRef + ")." + dialect.quoteIdentifier(subName));
            }
            return "'" + name + "', " + dialect.buildObject(subParts);
        }

        // Scalar column
        String colRef = alias + "." + dialect.quoteIdentifier(name);
        colRef += applySuffixCast(schemaInfo.getColumnType(tableName, name));
        // Enum columns: uppercase for GraphQL convention
        String enumType = schemaInfo.getEnumType(tableName, name);
        if (enumType != null && schemaInfo.getEnumTypes().containsKey(enumType)) {
            colRef = dialect.enumToText(colRef);
        }
        return "'" + name + "', " + colRef;
    }

    private String buildForwardFkPair(Field field, String alias, String name, SchemaInfo.FkInfo fk) {
        String subAlias = dialect.randAlias();
        String subObj = buildObject(field.getSelectionSet(), fk.refTable(), subAlias);
        // Build multi-column WHERE join for composite FKs
        List<String> joinConds = new ArrayList<>();
        for (int i = 0; i < fk.fkColumns().size(); i++) {
            joinConds.add(subAlias + "." + dialect.quoteIdentifier(fk.refColumns().get(i))
                    + " = " + alias + "." + dialect.quoteIdentifier(fk.fkColumns().get(i)));
        }
        return "'" + name + "', (" + SELECT + subObj
                + FROM + qualifiedTable(fk.refTable()) + " " + subAlias
                + WHERE + String.join(AND, joinConds) + ")";
    }

    private String buildReverseFkPair(Field field, String alias, String name, SchemaInfo.ReverseFkInfo rfk) {
        String subAlias = dialect.randAlias();
        String subObj = buildObject(field.getSelectionSet(), rfk.childTable(), subAlias);
        // Build multi-column WHERE join for composite FKs
        List<String> joinConds = new ArrayList<>();
        for (int i = 0; i < rfk.fkColumns().size(); i++) {
            joinConds.add(subAlias + "." + dialect.quoteIdentifier(rfk.fkColumns().get(i))
                    + " = " + alias + "." + dialect.quoteIdentifier(rfk.refColumns().get(i)));
        }
        return "'" + name + "', (" + SELECT + dialect.coalesceArray(dialect.aggregateArray(subObj))
                + FROM + qualifiedTable(rfk.childTable()) + " " + subAlias
                + WHERE + String.join(AND, joinConds) + ")";
    }

    private String buildComputedFieldPair(String tableName, String alias, String name) {
        List<SchemaInfo.ComputedField> computed = schemaInfo.getComputedFields(tableName);
        if (computed == null) return null;
        String rawTable = tableName.contains(".") ? tableName.substring(tableName.indexOf('.') + 1) : tableName;
        for (SchemaInfo.ComputedField cf : computed) {
            // Match: field name = function name, or function name = tableName_fieldName
            if (cf.functionName().equals(name) || cf.functionName().equals(rawTable + "_" + name)) {
                String funcCall = schemaInfo.resolveSchema(tableName, dbSchema) + "." + dialect.quoteIdentifier(cf.functionName()) + "(" + alias + ")";
                return "'" + name + "', " + funcCall;
            }
        }
        return null;
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

    Object decodeCursor(String cursor) {
        String decoded = new String(Base64.getDecoder().decode(cursor));
        // Try to parse as number for proper comparison with PK columns
        try {
            return Integer.parseInt(decoded);
        } catch (NumberFormatException _) {
            try {
                return Long.parseLong(decoded);
            } catch (NumberFormatException _) {
                return decoded;
            }
        }
    }

    private String getPk(String tableName) {
        return schemaInfo.getPrimaryKey(tableName);
    }

    private String reverseDir(String dir) {
        return ASC.equalsIgnoreCase(dir) ? DESC : ASC;
    }

    private String applySuffixCast(String type) {
        if (type == null) return "";
        return dialect.suffixCast(type);
    }
}
