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

    private final SchemaInfo schemaInfo;
    private final SqlDialect dialect;
    private final FilterBuilder filterBuilder;
    private final VectorSearchBuilder vectorSearchBuilder;
    private final String dbSchema;
    private final int maxRows;
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

        // ORDER BY — vector search overrides user-supplied orderBy entirely
        if (vectorClause.isPresent()) {
            sql.append(ORDER_BY).append(vectorClause.get().orderByFragment());
        } else if (!distinctOnCols.isEmpty()) {
            // DISTINCT ON requires the distinct columns to appear first in ORDER BY
            List<String> orderClauses = new ArrayList<>();
            for (String col : distinctOnCols) {
                orderClauses.add(alias + "." + dialect.quoteIdentifier(col) + " " + ASC);
            }
            // Append any user-specified ORDER BY columns (that aren't already in distinctOn)
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
            sql.append(ORDER_BY).append(joinCols(orderClauses));
        } else {
            // ORDER BY
            filterBuilder.applyOrderBy(sql, field, alias);
        }

        // LIMIT — vector.limitOverride takes precedence; otherwise the normal path
        if (vectorClause.isPresent() && vectorClause.get().limitOverride() != null) {
            int vlimit = Math.min(vectorClause.get().limitOverride(), maxRows);
            String paramName = "p_limit_" + params.size();
            sql.append(LIMIT).append(":").append(paramName);
            params.put(paramName, vlimit);
        } else {
            filterBuilder.applyLimit(sql, field, alias, params);
        }

        sql.append(") ").append(alias);
        return sql.toString();
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
                // Nested per-column: sum { total_amount }, avg { total_amount }
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

        // Parse connection selections
        SelectionSet edgesNodeSS = null;
        boolean wantsCursor = false;
        boolean wantsTotalCount = false;
        boolean wantsPageInfo = false;
        Set<String> pageInfoFields = new HashSet<>();

        for (Selection<?> s : field.getSelectionSet().getSelections()) {
            if (s instanceof Field f) {
                String fname = f.getName();
                if (FIELD_EDGES.equals(fname)) {
                    if (f.getSelectionSet() != null) {
                        for (Selection<?> es : f.getSelectionSet().getSelections()) {
                            if (es instanceof Field ef) {
                                if (FIELD_NODE.equals(ef.getName())) edgesNodeSS = ef.getSelectionSet();
                                if (FIELD_CURSOR.equals(ef.getName())) wantsCursor = true;
                            }
                        }
                    }
                } else if (FIELD_TOTAL_COUNT.equals(fname)) {
                    wantsTotalCount = true;
                } else if (FIELD_PAGE_INFO.equals(fname)) {
                    wantsPageInfo = true;
                    if (f.getSelectionSet() != null) {
                        for (Selection<?> ps : f.getSelectionSet().getSelections()) {
                            if (ps instanceof Field pf) pageInfoFields.add(pf.getName());
                        }
                    }
                }
            }
        }

        // Parse pagination args: first, last, after, before
        Integer first = null, last = null;
        String afterCursor = null, beforeCursor = null;
        for (Argument arg : field.getArguments()) {
            String argName = arg.getName();
            Map<String, Object> vars = FilterBuilder.CURRENT_VARIABLES.isBound() ? FilterBuilder.CURRENT_VARIABLES.get() : Map.of();
            if (ARG_FIRST.equals(argName)) {
                Integer v = filterBuilder.resolveIntArg(arg.getValue(), vars);
                if (v != null) first = Math.min(v, maxRows);
            } else if (ARG_LAST.equals(argName)) {
                Integer v = filterBuilder.resolveIntArg(arg.getValue(), vars);
                if (v != null) last = Math.min(v, maxRows);
            } else if (ARG_AFTER.equals(argName)) {
                afterCursor = filterBuilder.resolveStringArg(arg.getValue(), vars);
            } else if (ARG_BEFORE.equals(argName)) {
                beforeCursor = filterBuilder.resolveStringArg(arg.getValue(), vars);
            }
        }

        // Determine order columns (default: PK ASC)
        List<String[]> orderCols = filterBuilder.parseOrderBy(field); // [col, dir] pairs
        if (orderCols.isEmpty()) orderCols.add(new String[]{pk, ASC});

        boolean isForward = (last == null); // forward pagination by default, backward if "last" is used
        int limit = isForward ? (first != null ? first : maxRows) : last;

        // === Build CTE-based Connection SQL ===
        StringBuilder sql = new StringBuilder();
        sql.append(WITH);

        // CTE 1: __records — filtered, ordered, limited rows
        String recordsCte = dialect.cteName(block, "_records");
        sql.append(recordsCte).append(AS_OPEN);
        sql.append(SELECT).append(block).append(".*");
        sql.append(FROM).append(qualifiedTable(tableName)).append(" ").append(block);

        // WHERE clause (filter + cursor conditions)
        List<String> conditions = new ArrayList<>();
        filterBuilder.buildWhereConditions(field, block, params, conditions, tableName);

        // Cursor-based WHERE: after → pk > decoded_cursor, before → pk < decoded_cursor
        if (afterCursor != null) {
            String paramName = namedParam(P_AFTER, params.size());
            conditions.add(block + "." + dialect.quoteIdentifier(pk) + " > :" + paramName);
            params.put(paramName, decodeCursor(afterCursor));
        }
        if (beforeCursor != null) {
            String paramName = namedParam(P_BEFORE, params.size());
            conditions.add(block + "." + dialect.quoteIdentifier(pk) + " < :" + paramName);
            params.put(paramName, decodeCursor(beforeCursor));
        }

        if (!conditions.isEmpty()) {
            sql.append(WHERE).append(String.join(AND, conditions));
        }

        // ORDER BY — reverse for backward pagination
        sql.append(ORDER_BY);
        List<String> orderClauses = new ArrayList<>();
        for (String[] oc : orderCols) {
            String dir = isForward ? oc[1] : reverseDir(oc[1]);
            orderClauses.add(block + "." + dialect.quoteIdentifier(oc[0]) + " " + dir);
        }
        sql.append(joinCols(orderClauses));

        // LIMIT: fetch one extra to detect hasNextPage/hasPreviousPage
        String limitParam = namedParam(P_LIMIT, params.size());
        sql.append(LIMIT).append(PARAM_PREFIX).append(limitParam);
        params.put(limitParam, limit + 1);
        sql.append(")");

        // CTE 2: __has_next_page
        boolean needsHasNext = wantsPageInfo && (pageInfoFields.isEmpty() || pageInfoFields.contains(FIELD_HAS_NEXT_PAGE));
        if (needsHasNext) {
            String hnParam = namedParam(P_HN_LIMIT, params.size());
            sql.append(", ").append(dialect.cteName(block, "_has_next")).append(AS_OPEN);
            sql.append(SELECT).append(COUNT_ALL).append(" > :").append(hnParam);
            params.put(hnParam, limit);
            sql.append(" AS val").append(FROM).append(recordsCte);
            sql.append(")");
        }

        // CTE 3: __has_previous_page
        boolean needsHasPrev = wantsPageInfo && (pageInfoFields.isEmpty() || pageInfoFields.contains(FIELD_HAS_PREVIOUS_PAGE));
        if (needsHasPrev) {
            sql.append(", ").append(dialect.cteName(block, "_has_prev")).append(AS_OPEN);
            if (afterCursor != null || !isForward) {
                // There's a previous page if afterCursor was provided, or we're paginating backward
                sql.append(SELECT).append("true AS val");
            } else {
                sql.append(SELECT).append("false AS val");
            }
            sql.append(")");
        }

        // CTE 4: __total_count (only if requested)
        if (wantsTotalCount) {
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

        // === Final SELECT from CTEs ===
        // Limit to actual page size (remove the extra +1 row)
        String pageBlock = dialect.randAlias();

        // Build edge object parts
        List<String> edgeParts = new ArrayList<>();
        if (wantsCursor) {
            edgeParts.add("'cursor', " + dialect.encodeCursor(pageBlock + "." + dialect.quoteIdentifier(pk)));
        }
        String nodeObj = edgesNodeSS != null ? buildObject(edgesNodeSS, tableName, pageBlock) : dialect.buildObject(List.of());
        edgeParts.add("'node', " + nodeObj);
        String edgeObj = dialect.buildObject(edgeParts);

        // Build edges subquery
        StringBuilder edgesSub = new StringBuilder();
        edgesSub.append(SELECT).append(dialect.aggregateArray(edgeObj));
        // For backward pagination, reverse the rows back to natural order
        if (!isForward) {
            // NOTE: JSON_ARRAYAGG with ORDER BY isn't supported the same way across dialects
            // For simplicity, we don't reorder inside aggregateArray here
        }
        edgesSub.append(FROM).append("(").append(SELECT).append("*").append(FROM).append(recordsCte);
        String pageLimitParam = namedParam(P_PAGE_LIMIT, params.size());
        edgesSub.append(LIMIT).append(PARAM_PREFIX).append(pageLimitParam);
        params.put(pageLimitParam, limit);
        edgesSub.append(") ").append(pageBlock);

        // Build root object parts
        List<String> rootParts = new ArrayList<>();
        rootParts.add("'edges', " + dialect.coalesceArray("(" + edgesSub + ")"));

        // totalCount
        if (wantsTotalCount) {
            rootParts.add("'totalCount', (" + SELECT + "val" + FROM + dialect.cteName(block, "_total") + ")");
        }

        // pageInfo
        if (wantsPageInfo) {
            List<String> piParts = new ArrayList<>();
            if (needsHasNext) {
                if (isForward) {
                    piParts.add("'hasNextPage', " + dialect.jsonBool("(" + SELECT + "val" + FROM + dialect.cteName(block, "_has_next") + ")"));
                } else {
                    piParts.add("'hasNextPage', " + dialect.jsonBoolLiteral(beforeCursor != null));
                }
            }
            if (needsHasPrev) {
                if (isForward) {
                    piParts.add("'hasPreviousPage', " + dialect.jsonBool("(" + SELECT + "val" + FROM + dialect.cteName(block, "_has_prev") + ")"));
                } else {
                    piParts.add("'hasPreviousPage', " + dialect.jsonBool("(" + SELECT + "val" + FROM + dialect.cteName(block, "_has_next") + ")"));
                }
            }
            if (pageInfoFields.contains(FIELD_START_CURSOR)) {
                String pkTextExpr = "(" + SELECT + "CAST(" + dialect.quoteIdentifier(pk) + " AS CHAR)" + FROM + recordsCte + LIMIT + "1)";
                piParts.add("'startCursor', (" + SELECT + dialect.encodeCursor(pkTextExpr) + ")");
            }
            if (pageInfoFields.contains(FIELD_END_CURSOR)) {
                String pkTextExpr = "(" + SELECT + "CAST(" + dialect.quoteIdentifier(pk) + " AS CHAR)" + FROM + recordsCte +
                        ORDER_BY + dialect.quoteIdentifier(pk) + " " + DESC + LIMIT + "1)";
                piParts.add("'endCursor', (" + SELECT + dialect.encodeCursor(pkTextExpr) + ")");
            }
            rootParts.add("'pageInfo', " + dialect.buildObject(piParts));
        }

        sql.append(" ").append(SELECT).append(dialect.buildObject(rootParts));
        return sql.toString();
    }

    // === JSON object builder (recursive with FK traversal) ===

    public String buildObject(SelectionSet selectionSet, String tableName, String alias) {
        if (selectionSet == null) return dialect.buildObject(List.of());

        List<String> pairs = new ArrayList<>();
        Set<String> columns = schemaInfo.getColumns(tableName);

        for (Field field : flattenSelections(selectionSet, fragmentsHolder.get())) {
            String name = field.getName();

            if (columns.contains(name)) {
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
                    pairs.add("'" + name + "', " + dialect.buildObject(subParts));
                    continue;
                }

                // Scalar column
                String colRef = alias + "." + dialect.quoteIdentifier(name);
                colRef += applySuffixCast(schemaInfo.getColumnType(tableName, name));
                // Enum columns: uppercase for GraphQL convention
                String enumType = schemaInfo.getEnumType(tableName, name);
                if (enumType != null && schemaInfo.getEnumTypes().containsKey(enumType)) {
                    colRef = dialect.enumToText(colRef);
                }
                pairs.add("'" + name + "', " + colRef);
                continue;
            }

            // Forward FK
            SchemaInfo.FkInfo fk = schemaInfo.getForwardFk(tableName, name);
            if (fk != null) {
                String subAlias = dialect.randAlias();
                String subObj = buildObject(field.getSelectionSet(), fk.refTable(), subAlias);
                // Build multi-column WHERE join for composite FKs
                List<String> joinConds = new ArrayList<>();
                for (int i = 0; i < fk.fkColumns().size(); i++) {
                    joinConds.add(subAlias + "." + dialect.quoteIdentifier(fk.refColumns().get(i))
                            + " = " + alias + "." + dialect.quoteIdentifier(fk.fkColumns().get(i)));
                }
                pairs.add("'" + name + "', (" + SELECT + subObj
                        + FROM + qualifiedTable(fk.refTable()) + " " + subAlias
                        + WHERE + String.join(AND, joinConds) + ")");
                continue;
            }

            // Reverse FK
            SchemaInfo.ReverseFkInfo rfk = schemaInfo.getReverseFk(tableName, name);
            if (rfk != null) {
                String subAlias = dialect.randAlias();
                String subObj = buildObject(field.getSelectionSet(), rfk.childTable(), subAlias);
                // Build multi-column WHERE join for composite FKs
                List<String> joinConds = new ArrayList<>();
                for (int i = 0; i < rfk.fkColumns().size(); i++) {
                    joinConds.add(subAlias + "." + dialect.quoteIdentifier(rfk.fkColumns().get(i))
                            + " = " + alias + "." + dialect.quoteIdentifier(rfk.refColumns().get(i)));
                }
                pairs.add("'" + name + "', (" + SELECT + dialect.coalesceArray(dialect.aggregateArray(subObj))
                        + FROM + qualifiedTable(rfk.childTable()) + " " + subAlias
                        + WHERE + String.join(AND, joinConds) + ")");
                continue;
            }

            // Computed fields
            List<SchemaInfo.ComputedField> computed = schemaInfo.getComputedFields(tableName);
            if (computed != null) {
                boolean found = false;
                for (SchemaInfo.ComputedField cf : computed) {
                    // Match: field name = function name, or function name = tableName_fieldName
                    String rawTable = tableName.contains(".") ? tableName.substring(tableName.indexOf('.') + 1) : tableName;
                    if (cf.functionName().equals(name) || cf.functionName().equals(rawTable + "_" + name)) {
                        String funcCall = schemaInfo.resolveSchema(tableName, dbSchema) + "." + dialect.quoteIdentifier(cf.functionName()) + "(" + alias + ")";
                        pairs.add("'" + name + "', " + funcCall);
                        found = true;
                        break;
                    }
                }
                if (found) continue;
            }
        }

        return dialect.buildObject(pairs);
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
        try { return Integer.parseInt(decoded); } catch (NumberFormatException ignored) {}
        try { return Long.parseLong(decoded); } catch (NumberFormatException ignored) {}
        return decoded;
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
