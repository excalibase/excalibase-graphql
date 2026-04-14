package io.github.excalibase.rest.compiler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.SqlDialect;
import io.github.excalibase.compiler.VectorSearchBuilder;
import io.github.excalibase.schema.SchemaInfo;
import org.springframework.jdbc.core.SqlParameterValue;

import java.sql.Types;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.excalibase.compiler.SqlKeywords.*;

public class RestQueryCompiler {

    private static final int MAX_IN_LIST = 1000;
    private static final int MAX_BULK_ROWS = 1000;
    private static final String CTE_INS = "ins";
    private static final String CTE_UPD = "upd";
    private static final String CTE_DEL = "del";
    private static final String ALIAS = "c";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SchemaInfo schemaInfo;
    private final SqlDialect dialect;
    private final String defaultSchema;
    private final int defaultMaxRows;

    public record CompiledResult(String sql, Map<String, Object> params) {}
    public record FilterSpec(String column, String operator, String value, boolean negated) {}
    public record OrderBySpec(String column, String direction, String nulls) {}
    public record OrCondition(List<FilterSpec> conditions) {}
    public record EmbedSpec(String relationName, List<String> columns, String fkHint, List<EmbedSpec> children) {
        public EmbedSpec(String relationName, List<String> columns) {
            this(relationName, columns, null, List.of());
        }
        public EmbedSpec(String relationName, List<String> columns, String fkHint) {
            this(relationName, columns, fkHint, List.of());
        }
    }
    public record SelectQuery(
        String table, List<String> columns, List<FilterSpec> filters,
        List<OrCondition> orConditions, List<EmbedSpec> embeds,
        List<OrderBySpec> orderBy, int limit, int offset, boolean includeCount,
        String afterCursor, String orderColumn
    ) {
        public SelectQuery(String table, List<String> columns, List<FilterSpec> filters,
                           List<OrCondition> orConditions, List<EmbedSpec> embeds,
                           List<OrderBySpec> orderBy, int limit, int offset, boolean includeCount) {
            this(table, columns, filters, orConditions, embeds, orderBy, limit, offset, includeCount, null, null);
        }
    }

    public RestQueryCompiler(SchemaInfo schemaInfo, SqlDialect dialect, String defaultSchema, int maxRows) {
        this.schemaInfo = schemaInfo;
        this.dialect = dialect;
        this.defaultSchema = defaultSchema;
        this.defaultMaxRows = maxRows;
    }

    public CompiledResult compileSelect(String table, List<String> columns, List<FilterSpec> filters,
                                        List<OrderBySpec> orderBy, int limit, int offset, boolean includeCount) {
        return compileSelect(new SelectQuery(table, columns, filters, null, List.of(), orderBy, limit, offset, includeCount));
    }

    public CompiledResult compileSelect(String table, List<String> columns, List<FilterSpec> filters,
                                        List<OrCondition> orConditions, List<EmbedSpec> embeds,
                                        List<OrderBySpec> orderBy, int limit, int offset, boolean includeCount) {
        return compileSelect(new SelectQuery(table, columns, filters, orConditions, embeds, orderBy, limit, offset, includeCount));
    }

    public CompiledResult compileSelect(SelectQuery q) {
        Set<String> knownCols = new HashSet<>(schemaInfo.getColumns(q.table()));
        List<String> columns = q.columns().stream().filter(knownCols::contains).toList();
        List<OrderBySpec> orderBy = q.orderBy() != null ? q.orderBy().stream().filter(o -> knownCols.contains(o.column())).toList() : null;
        List<FilterSpec> allFilters = q.filters().stream().filter(f -> knownCols.contains(f.column())).toList();

        String quotedTable = resolveTable(q.table());
        Map<String, Object> params = new LinkedHashMap<>();

        // Extract the (optional) vector filter before WHERE. k-NN search is not
        // a predicate — it modifies ORDER BY + LIMIT — so the vector FilterSpec
        // must not land in buildWhere. Only the first vector filter is honored.
        VectorSearchBuilder.VectorClause vectorClause = null;
        List<FilterSpec> filters;
        {
            List<FilterSpec> remaining = new ArrayList<>(allFilters.size());
            for (FilterSpec f : allFilters) {
                if ("vector".equals(f.operator()) && vectorClause == null) {
                    vectorClause = compileVectorFilter(f, ALIAS, params);
                    continue;
                }
                remaining.add(f);
            }
            filters = remaining;
        }

        StringBuilder where = buildWhere(filters, ALIAS, P_FILTER, params, q.table());
        appendOrConditions(where, q.orConditions(), knownCols, params, q.table());
        if (q.afterCursor() != null && q.orderColumn() != null && knownCols.contains(q.orderColumn())) {
            if (!where.isEmpty()) where.append(AND);
            params.put(P_AFTER, convertValue(q.afterCursor(), q.table(), q.orderColumn()));
            where.append(dialect.quoteIdentifier(q.orderColumn())).append(GT).append(PARAM_PREFIX).append(P_AFTER);
        }

        // Vector k-NN ordering overrides any user-supplied orderBy entirely —
        // nearest-neighbor similarity IS the sort, there is no composing.
        StringBuilder orderBySql;
        int effectiveLimit = q.limit();
        if (vectorClause != null) {
            orderBySql = new StringBuilder(ORDER_BY).append(vectorClause.orderByFragment());
            if (vectorClause.limitOverride() != null) {
                effectiveLimit = Math.min(vectorClause.limitOverride(), defaultMaxRows);
            }
        } else {
            orderBySql = buildOrderBy(orderBy);
        }

        StringBuilder inner = buildInnerSelect(quotedTable, where, orderBySql, effectiveLimit, q.offset());
        String jsonAgg = buildJsonAgg(columns, buildEmbedEntries(q.table(), q.embeds()), knownCols);

        StringBuilder sql = new StringBuilder();
        sql.append(SELECT).append(jsonAgg).append(AS_BODY);
        if (q.includeCount()) appendCountSubquery(sql, filters, quotedTable, params, q.table());
        sql.append(FROM).append(parens(inner.toString())).append(SPACE).append(ALIAS);
        return new CompiledResult(sql.toString(), params);
    }

    /**
     * Compile a {@code vector.{json}} filter into a k-NN ordering clause via
     * {@link VectorSearchBuilder}. The JSON body is parsed once and delegated
     * to the Map-based builder API so this code doesn't depend on the graphql
     * AST. Returns null on malformed input (dropped silently, matching the
     * graphql side's behavior for absent / invalid vector arguments).
     */
    @SuppressWarnings("unchecked")
    private VectorSearchBuilder.VectorClause compileVectorFilter(
            FilterSpec filter, String tableAlias, Map<String, Object> params) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> shape = mapper.readValue(filter.value(), Map.class);
            // The column name is the URL key (e.g. ?embedding=vector.{...}), not
            // a field inside the JSON — ensure the shape carries it for the builder.
            shape.putIfAbsent("column", filter.column());
            VectorSearchBuilder builder = new VectorSearchBuilder(dialect);
            return builder.buildFromMap(shape, tableAlias, schemaInfo, params).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    public CompiledResult compileInsert(String table, Map<String, Object> input) {
        String quotedTable = resolveTable(table);
        Set<String> knownCols = new HashSet<>(schemaInfo.getColumns(table));
        Map<String, Object> params = new LinkedHashMap<>();
        List<String> cols = new ArrayList<>();
        List<String> vals = new ArrayList<>();
        int i = 0;
        for (var entry : input.entrySet()) {
            if (!knownCols.contains(entry.getKey())) continue;
            String pn = P_INSERT + (i++);
            cols.add(dialect.quoteIdentifier(entry.getKey()));
            vals.add(PARAM_PREFIX + pn);
            params.put(pn, coerceParam(table, entry.getKey(), entry.getValue()));
        }
        return new CompiledResult(
            WITH + CTE_INS + AS_OPEN + INSERT_INTO + quotedTable
            + parens(joinCols(cols)) + VALUES + parens(String.join(COMMA_SEP, vals))
            + RETURNING_ALL + SPACE + SELECT + dialect.rowToJson(CTE_INS) + FROM + CTE_INS,
            params);
    }

    public CompiledResult compileBulkInsert(String table, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) throw new IllegalArgumentException("Empty bulk insert");
        if (rows.size() > MAX_BULK_ROWS) throw new IllegalArgumentException("Bulk insert exceeds maximum of " + MAX_BULK_ROWS);
        String quotedTable = resolveTable(table);
        Set<String> knownCols = new HashSet<>(schemaInfo.getColumns(table));
        Map<String, Object> params = new LinkedHashMap<>();

        Set<String> allCols = new LinkedHashSet<>();
        for (var row : rows) row.keySet().stream().filter(knownCols::contains).forEach(allCols::add);
        List<String> colNames = allCols.stream().toList();

        List<String> valueRows = new ArrayList<>();
        for (int r = 0; r < rows.size(); r++) {
            List<String> vals = new ArrayList<>();
            for (String col : colNames) {
                String pn = P_INSERT + r + UNDERSCORE + col;
                params.put(pn, coerceParam(table, col, rows.get(r).getOrDefault(col, null)));
                vals.add(PARAM_PREFIX + pn);
            }
            valueRows.add(parens(String.join(COMMA_SEP, vals)));
        }
        List<String> quotedCols = colNames.stream().map(dialect::quoteIdentifier).toList();
        return new CompiledResult(
            WITH + CTE_INS + AS_OPEN + INSERT_INTO + quotedTable
            + parens(joinCols(new ArrayList<>(quotedCols))) + VALUES + String.join(COMMA_SEP, valueRows)
            + RETURNING_ALL + SPACE + SELECT + dialect.coalesceArray(dialect.aggregateArray(dialect.rowToJson(CTE_INS))) + FROM + CTE_INS,
            params);
    }

    public CompiledResult compileUpsert(String table, Map<String, Object> input, List<String> conflictCols) {
        String quotedTable = resolveTable(table);
        Set<String> knownCols = new HashSet<>(schemaInfo.getColumns(table));
        Map<String, Object> params = new LinkedHashMap<>();
        List<String> cols = new ArrayList<>();
        List<String> vals = new ArrayList<>();
        int i = 0;
        for (var entry : input.entrySet()) {
            if (!knownCols.contains(entry.getKey())) continue;
            String pn = P_INSERT + (i++);
            cols.add(dialect.quoteIdentifier(entry.getKey()));
            vals.add(PARAM_PREFIX + pn);
            params.put(pn, coerceParam(table, entry.getKey(), entry.getValue()));
        }
        List<String> quotedConflict = conflictCols.stream().map(dialect::quoteIdentifier).toList();
        List<String> updateSets = new ArrayList<>();
        for (var entry : input.entrySet()) {
            if (!conflictCols.contains(entry.getKey()) && knownCols.contains(entry.getKey())) {
                String qCol = dialect.quoteIdentifier(entry.getKey());
                updateSets.add(qCol + ASSIGN + EXCLUDED + DOT + qCol);
            }
        }
        String onConflict = ON_CONFLICT + parens(String.join(COMMA_SEP, quotedConflict))
            + (updateSets.isEmpty() ? DO_NOTHING : DO_UPDATE + SET + String.join(COMMA_SEP, updateSets));

        return new CompiledResult(
            WITH + CTE_INS + AS_OPEN + INSERT_INTO + quotedTable
            + parens(joinCols(cols)) + VALUES + parens(String.join(COMMA_SEP, vals))
            + onConflict + RETURNING_ALL + SPACE + SELECT + dialect.rowToJson(CTE_INS) + FROM + CTE_INS,
            params);
    }

    public CompiledResult compileUpdate(String table, Map<String, Object> input, List<FilterSpec> filters) {
        if (filters == null || filters.isEmpty()) throw new IllegalArgumentException("Update requires at least one filter");
        String quotedTable = resolveTable(table);
        Set<String> knownCols = new HashSet<>(schemaInfo.getColumns(table));
        Map<String, Object> params = new LinkedHashMap<>();
        List<String> setClauses = new ArrayList<>();
        int i = 0;
        for (var entry : input.entrySet()) {
            if (!knownCols.contains(entry.getKey())) continue;
            String pn = P_UPDATE + (i++);
            setClauses.add(dialect.quoteIdentifier(entry.getKey()) + ASSIGN + PARAM_PREFIX + pn);
            params.put(pn, coerceParam(table, entry.getKey(), entry.getValue()));
        }
        StringBuilder where = buildWhere(filters, quotedTable, P_WHERE_FILTER, params, table);
        return new CompiledResult(
            WITH + CTE_UPD + AS_OPEN + UPDATE + quotedTable
            + SET + String.join(COMMA_SEP, setClauses) + WHERE + where
            + RETURNING_ALL + SPACE + SELECT + dialect.coalesceArray(dialect.aggregateArray(dialect.rowToJson(CTE_UPD))) + FROM + CTE_UPD,
            params);
    }

    public CompiledResult compileDelete(String table, List<FilterSpec> filters) {
        if (filters == null || filters.isEmpty()) throw new IllegalArgumentException("Delete requires at least one filter");
        String quotedTable = resolveTable(table);
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder where = buildWhere(filters, quotedTable, P_DELETE_FILTER, params, table);
        return new CompiledResult(
            WITH + CTE_DEL + AS_OPEN + DELETE_FROM + quotedTable
            + WHERE + where + RETURNING_ALL + SPACE
            + SELECT + dialect.coalesceArray(dialect.aggregateArray(dialect.rowToJson(CTE_DEL))) + FROM + CTE_DEL,
            params);
    }


    private String resolveTable(String table) {
        String schema = schemaInfo.getTableSchema(table);
        if (schema == null) schema = defaultSchema;
        String raw = table.contains(DOT) ? table.substring(table.indexOf(DOT) + 1) : table;
        return dialect.qualifiedTable(schema, raw);
    }

    private StringBuilder buildWhere(List<FilterSpec> filters, String ref, String prefix, Map<String, Object> params, String table) {
        StringBuilder where = new StringBuilder();
        int fc = 0;
        for (FilterSpec f : filters) {
            if (!where.isEmpty()) where.append(AND);
            where.append(buildFilterSql(f, ref, prefix + (fc++), params, table));
        }
        return where;
    }

    private void appendOrConditions(StringBuilder where, List<OrCondition> ors, Set<String> knownCols, Map<String, Object> params, String table) {
        if (ors == null) return;
        for (int oi = 0; oi < ors.size(); oi++) {
            List<String> parts = new ArrayList<>();
            for (int ci = 0; ci < ors.get(oi).conditions().size(); ci++) {
                var f = ors.get(oi).conditions().get(ci);
                if (knownCols.contains(f.column())) parts.add(buildFilterSql(f, ALIAS, P_OR + oi + UNDERSCORE + ci, params, table));
            }
            if (!parts.isEmpty()) {
                if (!where.isEmpty()) where.append(AND);
                where.append(parens(String.join(OR, parts)));
            }
        }
    }

    private StringBuilder buildInnerSelect(String quotedTable, StringBuilder where, StringBuilder orderSql, int limit, int offset) {
        StringBuilder inner = new StringBuilder();
        inner.append(SELECT).append(STAR).append(FROM).append(quotedTable).append(SPACE).append(ALIAS);
        if (!where.isEmpty()) inner.append(WHERE).append(where);
        inner.append(orderSql);
        inner.append(LIMIT).append(limit > 0 ? limit : defaultMaxRows);
        if (offset > 0) inner.append(OFFSET).append(offset);
        return inner;
    }

    private StringBuilder buildOrderBy(List<OrderBySpec> orderBy) {
        StringBuilder sql = new StringBuilder();
        if (orderBy != null && !orderBy.isEmpty()) {
            sql.append(ORDER_BY);
            List<String> parts = new ArrayList<>();
            for (OrderBySpec o : orderBy) {
                String part = ALIAS + DOT + dialect.quoteIdentifier(o.column()) + SPACE + o.direction();
                if (o.nulls() != null) part += SPACE + o.nulls();
                parts.add(part);
            }
            sql.append(String.join(COMMA_SEP, parts));
        }
        return sql;
    }

    private String buildJsonAgg(List<String> columns, List<String> embedSql, Set<String> knownCols) {
        if (columns.isEmpty() && embedSql.isEmpty()) return dialect.coalesceArray(dialect.aggregateArray(dialect.rowToJson(ALIAS)));
        List<String> entries = new ArrayList<>();
        for (String col : columns.isEmpty() ? new ArrayList<>(knownCols) : columns) {
            entries.add(sqlString(col) + COMMA_SEP + ALIAS + DOT + dialect.quoteIdentifier(col));
        }
        entries.addAll(embedSql);
        return dialect.coalesceArray(dialect.aggregateArray(dialect.buildObject(entries)));
    }

    private void appendCountSubquery(StringBuilder sql, List<FilterSpec> filters, String quotedTable, Map<String, Object> params, String table) {
        StringBuilder cw = buildWhere(filters, quotedTable, P_FILTER_COUNT, params, table);
        sql.append(COMMA_SEP).append(parens(SELECT + COUNT_ALL + FROM + quotedTable + (cw.isEmpty() ? "" : WHERE + cw))).append(AS_TOTAL_COUNT);
    }


    private List<String> buildEmbedEntries(String table, List<EmbedSpec> embeds) {
        return buildEmbedEntries(table, embeds, ALIAS, new AtomicInteger());
    }

    private List<String> buildEmbedEntries(String table, List<EmbedSpec> embeds, String parentAlias, AtomicInteger counter) {
        if (embeds == null || embeds.isEmpty()) return List.of();
        List<String> entries = new ArrayList<>();
        for (EmbedSpec embed : embeds) {
            String ia = ALIAS_R + counter.getAndIncrement();
            String oa = ALIAS_R + counter.getAndIncrement();
            var fwd = findForwardFk(table, embed.relationName(), embed.fkHint());
            if (fwd != null) entries.add(buildForwardEmbed(embed, fwd, ia, oa, parentAlias, counter));
            else {
                var rev = findReverseFk(table, embed.relationName(), embed.fkHint());
                if (rev != null) entries.add(buildReverseEmbed(embed, rev, ia, oa, parentAlias, counter));
            }
        }
        return entries;
    }

    private String buildForwardEmbed(EmbedSpec embed, SchemaInfo.FkInfo fk, String ia, String oa,
                                     String parentAlias, AtomicInteger counter) {
        String refTable = resolveTable(fk.refTable());
        List<String> childEntries = buildEmbedEntries(fk.refTable(), embed.children(), oa, counter);
        String innerSel = childEntries.isEmpty() ? buildEmbedSelect(embed, ia, fk.refTable()) : SELECT + ia + DOT_STAR;
        String rowExpr = buildRowExpr(embed, oa, fk.refTable(), childEntries);
        return sqlString(embed.relationName()) + COMMA_SEP + parens(
            SELECT + rowExpr + FROM
            + parens(innerSel + FROM + refTable + SPACE + ia
            + WHERE + ia + DOT + dialect.quoteIdentifier(fk.refColumn())
            + ASSIGN + parentAlias + DOT + dialect.quoteIdentifier(fk.fkColumn()))
            + SPACE + oa);
    }

    private String buildReverseEmbed(EmbedSpec embed, SchemaInfo.ReverseFkInfo rev, String ia, String oa,
                                     String parentAlias, AtomicInteger counter) {
        String childTable = resolveTable(rev.childTable());
        List<String> childEntries = buildEmbedEntries(rev.childTable(), embed.children(), oa, counter);
        String innerSel = childEntries.isEmpty() ? buildEmbedSelect(embed, ia, rev.childTable()) : SELECT + ia + DOT_STAR;
        String rowExpr = buildRowExpr(embed, oa, rev.childTable(), childEntries);
        return sqlString(embed.relationName()) + COMMA_SEP + COALESCE + parens(
            parens(SELECT + FN_JSON_AGG + parens(rowExpr) + FROM
            + parens(innerSel + FROM + childTable + SPACE + ia
            + WHERE + ia + DOT + dialect.quoteIdentifier(rev.fkColumn())
            + ASSIGN + parentAlias + DOT + dialect.quoteIdentifier(rev.refColumns().get(0)))
            + SPACE + oa) + COMMA_SEP + EMPTY_JSON_ARRAY);
    }

    /**
     * Build the row expression for an embed. When there are no children, use rowToJson for
     * simplicity. When there are children, build an explicit jsonb_build_object that includes
     * both the requested columns and the child embed entries.
     */
    private String buildRowExpr(EmbedSpec embed, String outerAlias, String table, List<String> childEntries) {
        if (childEntries.isEmpty()) return dialect.rowToJson(outerAlias + DOT_STAR);
        List<String> entries = new ArrayList<>();
        List<String> cols = embed.columns().isEmpty() || embed.columns().contains(STAR)
            ? new ArrayList<>(schemaInfo.getColumns(table))
            : embed.columns();
        for (String col : cols) {
            entries.add(sqlString(col) + COMMA_SEP + outerAlias + DOT + dialect.quoteIdentifier(col));
        }
        entries.addAll(childEntries);
        return dialect.buildObject(entries);
    }

    private String buildEmbedSelect(EmbedSpec embed, String alias, String table) {
        if (embed.columns().isEmpty() || embed.columns().contains(STAR)) return SELECT + alias + DOT + STAR;
        Set<String> known = new HashSet<>(schemaInfo.getColumns(table));
        List<String> safe = embed.columns().stream().filter(known::contains).map(c -> alias + DOT + dialect.quoteIdentifier(c)).toList();
        return safe.isEmpty() ? SELECT + alias + DOT + STAR : SELECT + String.join(COMMA_SEP, safe);
    }

    private SchemaInfo.FkInfo findForwardFk(String table, String relName, String fkHint) {
        for (var e : schemaInfo.getAllForwardFks().entrySet()) {
            if (!e.getKey().startsWith(table + DOT)) continue;
            if (fkHint != null && !e.getValue().fkColumn().equals(fkHint)) continue;
            String ref = e.getValue().refTable();
            String raw = ref.contains(DOT) ? ref.substring(ref.indexOf(DOT) + 1) : ref;
            if (raw.equals(relName)) return e.getValue();
        }
        return null;
    }

    private SchemaInfo.ReverseFkInfo findReverseFk(String table, String relName, String fkHint) {
        for (var e : schemaInfo.getAllReverseFks().entrySet()) {
            if (!e.getKey().startsWith(table + DOT)) continue;
            if (fkHint != null && !e.getValue().fkColumn().equals(fkHint)) continue;
            String child = e.getValue().childTable();
            String raw = child.contains(DOT) ? child.substring(child.indexOf(DOT) + 1) : child;
            if (raw.equals(relName)) return e.getValue();
        }
        return null;
    }


    private String buildFilterSql(FilterSpec filter, String ref, String paramName, Map<String, Object> params, String table) {
        String colRef = dialect.quoteIdentifier(filter.column());
        String neg = filter.negated() ? NOT : "";
        return switch (filter.operator()) {
            case "eq" -> comparison(params, paramName, filter, neg, colRef, ASSIGN, table);
            case "neq" -> comparison(params, paramName, filter, neg, colRef, NEQ, table);
            case "gt" -> comparison(params, paramName, filter, neg, colRef, GT, table);
            case "gte" -> comparison(params, paramName, filter, neg, colRef, GTE, table);
            case "lt" -> comparison(params, paramName, filter, neg, colRef, LT, table);
            case "lte" -> comparison(params, paramName, filter, neg, colRef, LTE, table);
            case "like" -> stringFilter(params, paramName, neg, colRef, LIKE, filter.value().replace('*', '%'));
            case "ilike" -> { params.put(paramName, filter.value().replace('*', '%')); yield neg + dialect.ilike(colRef, PARAM_PREFIX + paramName); }
            case "startswith" -> stringFilter(params, paramName, neg, colRef, LIKE, filter.value() + "%");
            case "endswith" -> stringFilter(params, paramName, neg, colRef, LIKE, "%" + filter.value());
            case "match" -> stringFilter(params, paramName, neg, colRef, REGEX_MATCH, filter.value());
            case "imatch" -> stringFilter(params, paramName, neg, colRef, REGEX_IMATCH, filter.value());
            case "is" -> isFilter(neg, colRef, filter.value());
            case "isnotnull" -> neg + colRef + IS_NOT_NULL;
            case "isdistinct" -> { params.put(paramName, convertValue(filter.value(), table, filter.column())); yield neg + colRef + IS_DISTINCT_FROM + PARAM_PREFIX + paramName; }
            case "in" -> buildInSql(filter, colRef, neg, paramName, params, false);
            case "notin" -> buildInSql(filter, colRef, neg, paramName, params, true);
            case "haskey" -> jsonFilter(params, paramName, filter, neg, FN_JSONB_EXISTS + parens(colRef + COMMA_SEP + PARAM_PREFIX + paramName));
            case "jsoncontains", "contains", "cs" -> jsonFilter(params, paramName, filter, neg, colRef + CONTAINS + PARAM_PREFIX + paramName + CAST_JSONB);
            case "jsoncontained", "containedin", "cd" -> jsonFilter(params, paramName, filter, neg, colRef + CONTAINED_BY + PARAM_PREFIX + paramName + CAST_JSONB);
            case "jsonpath" -> jsonFilter(params, paramName, filter, neg, colRef + JSONPATH_EXISTS + PARAM_PREFIX + paramName + CAST_JSONPATH);
            case "jsonpathexists" -> jsonFilter(params, paramName, filter, neg, colRef + MATCH_TSQUERY + PARAM_PREFIX + paramName + CAST_JSONPATH);
            case "arraycontains" -> jsonFilter(params, paramName, filter, neg, colRef + CONTAINS + ARRAY_PREFIX + PARAM_PREFIX + paramName + "]");
            case "arrayhasany", "ov" -> jsonFilter(params, paramName, filter, neg, colRef + OVERLAPS + PARAM_PREFIX + paramName);
            case "arrayhasall" -> jsonFilter(params, paramName, filter, neg, colRef + CONTAINS + ARRAY_PREFIX + PARAM_PREFIX + paramName + "]");
            case "arraylength" -> { params.put(paramName, convertValue(filter.value(), table, filter.column())); yield neg + FN_ARRAY_LENGTH + parens(colRef + COMMA_SEP + "1") + ASSIGN + PARAM_PREFIX + paramName; }
            case "fts" -> tsFilter(params, paramName, filter, neg, colRef, FN_TO_TSQUERY);
            case "plfts" -> tsFilter(params, paramName, filter, neg, colRef, FN_PLAINTO_TSQUERY);
            case "phfts" -> tsFilter(params, paramName, filter, neg, colRef, FN_PHRASETO_TSQUERY);
            case "wfts" -> tsFilter(params, paramName, filter, neg, colRef, FN_WEBSEARCH_TSQUERY);
            case "sl" -> jsonFilter(params, paramName, filter, neg, colRef + STRICTLY_LEFT + PARAM_PREFIX + paramName);
            case "sr" -> jsonFilter(params, paramName, filter, neg, colRef + STRICTLY_RIGHT + PARAM_PREFIX + paramName);
            case "nxl" -> jsonFilter(params, paramName, filter, neg, colRef + NO_EXTEND_LEFT + PARAM_PREFIX + paramName);
            case "nxr" -> jsonFilter(params, paramName, filter, neg, colRef + NO_EXTEND_RIGHT + PARAM_PREFIX + paramName);
            case "adj" -> jsonFilter(params, paramName, filter, neg, colRef + ADJACENT + PARAM_PREFIX + paramName);
            default -> throw new IllegalArgumentException("Unsupported filter operator: " + filter.operator());
        };
    }

    private String comparison(Map<String, Object> params, String pn, FilterSpec f, String neg, String col, String op, String table) {
        params.put(pn, convertValue(f.value(), table, f.column()));
        return neg + col + op + PARAM_PREFIX + pn;
    }

    private String stringFilter(Map<String, Object> params, String pn, String neg, String col, String op, String val) {
        params.put(pn, val);
        return neg + col + op + PARAM_PREFIX + pn;
    }

    private String jsonFilter(Map<String, Object> params, String pn, FilterSpec f, String neg, String expr) {
        params.put(pn, f.value());
        return neg + expr;
    }

    private String tsFilter(Map<String, Object> params, String pn, FilterSpec f, String neg, String col, String fn) {
        params.put(pn, f.value());
        return neg + FN_TO_TSVECTOR + parens(col) + MATCH_TSQUERY + fn + parens(PARAM_PREFIX + pn);
    }

    private String isFilter(String neg, String col, String value) {
        return switch (value.toLowerCase()) {
            case "null" -> neg + col + IS_NULL;
            case "true" -> neg + col + IS_TRUE;
            case "false" -> neg + col + IS_FALSE;
            default -> neg + col + IS_NULL;
        };
    }

    private String buildInSql(FilterSpec filter, String col, String neg, String pn, Map<String, Object> params, boolean not) {
        String inner = filter.value();
        if (inner.startsWith("(") && inner.endsWith(")")) inner = inner.substring(1, inner.length() - 1);
        String[] items = inner.split(",");
        if (items.length > MAX_IN_LIST) throw new IllegalArgumentException("IN list exceeds maximum of " + MAX_IN_LIST);
        List<String> ph = new ArrayList<>();
        for (int i = 0; i < items.length; i++) {
            String p = pn + UNDERSCORE + i;
            params.put(p, items[i].trim());
            ph.add(PARAM_PREFIX + p);
        }
        return neg + col + (not ? NOT_IN : IN) + parens(String.join(COMMA_SEP, ph));
    }

    /**
     * Coerce a value from a JSON body (Object) for binding.
     *
     * <p>Uses {@code Types.OTHER} for PostgreSQL-specific types so the server — not the
     * JDBC driver — performs the cast.  Timestamps accept many input formats (ISO-8601,
     * RFC-2822, "epoch", "now", etc.); binding as {@code Types.OTHER} sends the value as
     * an untyped text literal and lets PostgreSQL's input function resolve the format,
     * exactly like {@code '2026-05-01T14:00:00Z'::timestamptz}.
     */
    private Object coerceParam(String table, String column, Object value) {
        if (value == null) return null;
        if (schemaInfo.getEnumType(table, column) != null) {
            return new SqlParameterValue(Types.OTHER, value.toString());
        }
        String type = schemaInfo.getColumnType(table, column);
        if (type == null) return value;
        return switch (type.toLowerCase()) {
            // Temporal types — with/without timezone, short aliases, array forms (_timestamp = pg ARRAY udt_name)
            case "timestamp", "timestamptz",
                 "timestamp with time zone", "timestamp without time zone",
                 "_timestamp", "_timestamptz", "timestamp[]", "timestamptz[]" ->
                new SqlParameterValue(Types.OTHER, value.toString());
            case "date", "_date", "date[]" ->
                new SqlParameterValue(Types.OTHER, value.toString());
            case "time", "timetz",
                 "time with time zone", "time without time zone",
                 "_time", "_timetz", "time[]", "timetz[]" ->
                new SqlParameterValue(Types.OTHER, value.toString());
            case "interval", "_interval", "interval[]" ->
                new SqlParameterValue(Types.OTHER, value.toString());
            // Other opaque server types
            case "uuid", "_uuid", "uuid[]" ->
                new SqlParameterValue(Types.OTHER, value.toString());
            case "_text", "text[]" ->
                new SqlParameterValue(Types.OTHER, value.toString());
            // JSONB/JSON: Map/List from JSON body must be serialized to a JSON string first
            case "jsonb", "json", "_jsonb", "_json", "jsonb[]", "json[]" -> {
                String json = (value instanceof String s) ? s : toJsonString(value);
                yield new SqlParameterValue(Types.OTHER, json);
            }
            default -> value;
        };
    }

    private String toJsonString(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return value.toString();
        }
    }

    /**
     * Coerce a filter value (String) for binding — converts to Java type for numeric columns,
     * delegates PostgreSQL-specific types to server-side casting via {@code Types.OTHER}.
     */
    private Object convertValue(String value, String table, String column) {
        if (schemaInfo.getEnumType(table, column) != null) {
            return new SqlParameterValue(Types.OTHER, value);
        }
        String type = schemaInfo.getColumnType(table, column);
        if (type == null) return value;
        try {
            return switch (type.toLowerCase()) {
                case "integer", "int4", "serial" -> Integer.parseInt(value);
                case "bigint", "int8", "bigserial" -> Long.parseLong(value);
                case "numeric", "decimal", "float8", "double precision" -> Double.parseDouble(value);
                case "real", "float4" -> Float.parseFloat(value);
                case "boolean", "bool" -> Boolean.parseBoolean(value);
                // Temporal types — server handles all format variations
                case "timestamp", "timestamptz",
                     "timestamp with time zone", "timestamp without time zone",
                     "_timestamp", "_timestamptz", "timestamp[]", "timestamptz[]" ->
                    new SqlParameterValue(Types.OTHER, value);
                case "date", "_date", "date[]" ->
                    new SqlParameterValue(Types.OTHER, value);
                case "time", "timetz",
                     "time with time zone", "time without time zone",
                     "_time", "_timetz", "time[]", "timetz[]" ->
                    new SqlParameterValue(Types.OTHER, value);
                case "interval", "_interval", "interval[]" ->
                    new SqlParameterValue(Types.OTHER, value);
                // Other opaque server types
                case "uuid", "_uuid", "uuid[]" ->
                    new SqlParameterValue(Types.OTHER, value);
                case "jsonb", "json", "_jsonb", "_json", "jsonb[]", "json[]" ->
                    new SqlParameterValue(Types.OTHER, value);
                case "_text", "text[]" ->
                    new SqlParameterValue(Types.OTHER, value);
                default -> value;
            };
        } catch (NumberFormatException e) {
            return value;
        }
    }
}
