package io.github.excalibase.rest.compiler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.SqlDialect;
import io.github.excalibase.compiler.VectorSearchBuilder;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.schema.TableAccess;
import io.github.excalibase.security.PermissionCheckFailedException;
import io.github.excalibase.security.RlsContext;
import io.github.excalibase.security.RlsOp;
import io.github.excalibase.security.RlsWhereContributor;
import io.github.excalibase.security.WriteGuard;
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
    private static final String OUT = "out";
    private static final String CHECK_ALIAS = "chk";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SchemaInfo schemaInfo;
    private final SqlDialect dialect;
    private final String defaultSchema;
    private final int defaultMaxRows;
    private final TableAccess access;

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
        this(schemaInfo, dialect, defaultSchema, maxRows, TableAccess.UNRESTRICTED);
    }

    /**
     * {@code schemaInfo} is the caller's view, so a table or column it may not read is not in it;
     * {@code access} adds what it may write, its row cap and its aggregation right.
     */
    public RestQueryCompiler(SchemaInfo schemaInfo, SqlDialect dialect, String defaultSchema, int maxRows,
                             TableAccess access) {
        this.schemaInfo = schemaInfo;
        this.dialect = dialect;
        this.defaultSchema = defaultSchema;
        this.defaultMaxRows = maxRows;
        this.access = access == null ? TableAccess.UNRESTRICTED : access;
    }

    public CompiledResult compileSelect(String table, List<String> columns, List<FilterSpec> filters,
                                        List<OrderBySpec> orderBy, int limit, int offset, boolean includeCount) {
        return compileSelect(new SelectQuery(table, columns, filters, null, List.of(), orderBy, limit, offset, includeCount));
    }

    public CompiledResult compileSelect(SelectQuery query) {
        return select(query, resolveTable(query.table()), new LinkedHashMap<>(), false);
    }

    /**
     * The rows a tracked function returns, read like {@code query.table()} (its return table): the
     * caller's select filter, columns and row cap on that table apply, as do the request's filters,
     * select, order, limit and offset. {@code callSql} is the call, its parameters already in
     * {@code params}. A single-row function skips the all-NULL row it yields when it returns NULL.
     */
    public CompiledResult compileFunctionSelect(SelectQuery query, String callSql, Map<String, Object> params,
                                                boolean singleRow) {
        if (query.includeCount()) {
            throw new IllegalArgumentException("Counting the rows of a function is not supported");
        }
        return select(query, callSql, params, singleRow);
    }

    private CompiledResult select(SelectQuery query, String quotedTable, Map<String, Object> params,
                                  boolean singleRow) {
        Set<String> knownCols = new HashSet<>(schemaInfo.getColumns(query.table()));
        requirePermittedSelect(query, knownCols);
        List<String> columns = query.columns().stream().filter(knownCols::contains).toList();
        List<OrderBySpec> orderBy = query.orderBy() != null ? query.orderBy().stream()
                .filter(o -> knownCols.contains(o.column())).toList() : null;
        List<FilterSpec> allFilters = query.filters().stream()
                .filter(f -> knownCols.contains(f.column())).toList();

        // k-NN search is not a predicate — it modifies ORDER BY + LIMIT — so the vector
        // FilterSpec must not land in buildWhere. Only the first vector filter is honored.
        List<FilterSpec> filters = new ArrayList<>(allFilters.size());
        VectorSearchBuilder.VectorClause vectorClause = splitVectorFilter(allFilters, filters, params);

        StringBuilder where = buildWhere(filters, P_FILTER, params, query.table());
        appendOrConditions(where, query.orConditions(), knownCols, params, query.table());
        appendAfterCursor(where, query, knownCols, params);
        if (singleRow) {
            if (!where.isEmpty()) where.append(AND);
            where.append("NOT (").append(ALIAS).append(" IS NULL)");
        }

        // RLS: filter rows the caller may not read (the inner SELECT aliases the
        // table as ALIAS, so relationship/EXISTS predicates correlate to it).
        appendRls(where, query.table(), ALIAS, RlsOp.SELECT, params);

        // Vector k-NN ordering overrides any user-supplied orderBy entirely —
        // nearest-neighbor similarity IS the sort, there is no composing.
        StringBuilder orderBySql;
        int effectiveLimit = query.limit();
        if (vectorClause != null) {
            orderBySql = new StringBuilder(ORDER_BY).append(vectorClause.orderByFragment());
            if (vectorClause.limitOverride() != null) {
                effectiveLimit = Math.min(vectorClause.limitOverride(), defaultMaxRows);
            }
        } else {
            orderBySql = buildOrderBy(orderBy);
        }
        effectiveLimit = capped(query.table(), effectiveLimit > 0 ? effectiveLimit : defaultMaxRows);

        StringBuilder inner = buildInnerSelect(query.table(), quotedTable, where, orderBySql, effectiveLimit,
                query.offset());
        String jsonAgg = buildJsonAgg(query.table(), columns, buildEmbedEntries(query.table(), query.embeds(), params), knownCols);

        StringBuilder sql = new StringBuilder();
        sql.append(SELECT).append(jsonAgg).append(AS_BODY);
        if (query.includeCount()) appendCountSubquery(sql, filters, quotedTable, params, query.table());
        sql.append(FROM).append(parens(inner.toString())).append(SPACE).append(ALIAS);
        return new CompiledResult(sql.toString(), params);
    }

    /** Moves every filter but the vector one honored into {@code predicates}; returns that one compiled. */
    private VectorSearchBuilder.VectorClause splitVectorFilter(List<FilterSpec> allFilters, List<FilterSpec> predicates,
                                                               Map<String, Object> params) {
        VectorSearchBuilder.VectorClause vectorClause = null;
        for (FilterSpec filter : allFilters) {
            if ("vector".equals(filter.operator()) && vectorClause == null) {
                vectorClause = compileVectorFilter(filter, ALIAS, params);
            } else {
                predicates.add(filter);
            }
        }
        return vectorClause;
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
            Map<String, Object> shape = MAPPER.readValue(filter.value(), Map.class);
            // The column name is the URL key (e.g. ?embedding=vector.{...}), not
            // a field inside the JSON — ensure the shape carries it for the builder.
            shape.putIfAbsent("column", filter.column());
            VectorSearchBuilder builder = new VectorSearchBuilder(dialect);
            return builder.buildFromMap(shape, tableAlias, schemaInfo, params).orElse(null);
        } catch (Exception _) {
            return null;
        }
    }

    public CompiledResult compileInsert(String table, Map<String, Object> input) {
        String quotedTable = resolveTable(table);
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> row = writable(table, RlsOp.INSERT, input);
        List<String> cols = new ArrayList<>();
        List<String> vals = new ArrayList<>();
        int i = 0;
        for (var entry : row.entrySet()) {
            String pn = P_INSERT + (i++);
            cols.add(dialect.quoteIdentifier(entry.getKey()));
            vals.add(PARAM_PREFIX + pn);
            params.put(pn, coerceParam(table, entry.getKey(), entry.getValue()));
        }
        String cte = WITH + CTE_INS + AS_OPEN + INSERT_INTO + quotedTable
                + parens(joinCols(cols)) + VALUES + parens(String.join(COMMA_SEP, vals)) + RETURNING_ALL;
        return new CompiledResult(cte + SPACE + output(table, CTE_INS, false, List.of(RlsOp.INSERT), params), params);
    }

    public CompiledResult compileBulkInsert(String table, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) throw new IllegalArgumentException("Empty bulk insert");
        if (rows.size() > MAX_BULK_ROWS) throw new IllegalArgumentException("Bulk insert exceeds maximum of " + MAX_BULK_ROWS);
        String quotedTable = resolveTable(table);
        Map<String, Object> params = new LinkedHashMap<>();
        List<Map<String, Object>> written = rows.stream().map(row -> writable(table, RlsOp.INSERT, row)).toList();

        Set<String> allCols = new LinkedHashSet<>();
        written.forEach(row -> allCols.addAll(row.keySet()));
        List<String> colNames = allCols.stream().toList();

        List<String> valueRows = new ArrayList<>();
        for (int rowIndex = 0; rowIndex < written.size(); rowIndex++) {
            List<String> vals = new ArrayList<>();
            for (String col : colNames) {
                String pn = P_INSERT + rowIndex + UNDERSCORE + col;
                params.put(pn, coerceParam(table, col, written.get(rowIndex).getOrDefault(col, null)));
                vals.add(PARAM_PREFIX + pn);
            }
            valueRows.add(parens(String.join(COMMA_SEP, vals)));
        }
        List<String> quotedCols = colNames.stream().map(dialect::quoteIdentifier).toList();
        String cte = WITH + CTE_INS + AS_OPEN + INSERT_INTO + quotedTable
                + parens(joinCols(new ArrayList<>(quotedCols))) + VALUES + String.join(COMMA_SEP, valueRows)
                + RETURNING_ALL;
        return new CompiledResult(cte + SPACE + output(table, CTE_INS, true, List.of(RlsOp.INSERT), params), params);
    }

    /**
     * INSERT … ON CONFLICT DO UPDATE: the insert half obeys the insert permission, the update half the
     * update permission's columns, presets and filter, and every written row must pass both checks.
     */
    public CompiledResult compileUpsert(String table, Map<String, Object> input, List<String> conflictCols) {
        String quotedTable = resolveTable(table);
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> row = writable(table, RlsOp.INSERT, input);
        List<String> cols = new ArrayList<>();
        List<String> vals = new ArrayList<>();
        int i = 0;
        for (var entry : row.entrySet()) {
            String pn = P_INSERT + (i++);
            cols.add(dialect.quoteIdentifier(entry.getKey()));
            vals.add(PARAM_PREFIX + pn);
            params.put(pn, coerceParam(table, entry.getKey(), entry.getValue()));
        }
        String onConflict = ON_CONFLICT + parens(String.join(COMMA_SEP,
                conflictCols.stream().map(dialect::quoteIdentifier).toList()))
                + doUpdate(table, input, conflictCols, params);
        String cte = WITH + CTE_INS + AS_OPEN + INSERT_INTO + quotedTable
                + parens(joinCols(cols)) + VALUES + parens(String.join(COMMA_SEP, vals)) + onConflict + RETURNING_ALL;
        return new CompiledResult(cte + SPACE
                + output(table, CTE_INS, false, List.of(RlsOp.INSERT, RlsOp.UPDATE), params), params);
    }

    /**
     * The DO UPDATE half. It can overwrite a pre-existing row, so it reaches only rows the caller may
     * update; columns are qualified with the target relation name because a bare column in
     * {@code ON CONFLICT … WHERE} is ambiguous with {@code EXCLUDED}.
     */
    private String doUpdate(String table, Map<String, Object> input, List<String> conflictCols,
                            Map<String, Object> params) {
        Map<String, Object> changed = new LinkedHashMap<>(input);
        conflictCols.forEach(changed::remove);
        Map<String, Object> updated = writable(table, RlsOp.UPDATE, changed);
        List<String> updateSets = new ArrayList<>();
        int i = 0;
        for (var entry : updated.entrySet()) {
            String qCol = dialect.quoteIdentifier(entry.getKey());
            if (changed.containsKey(entry.getKey())) {
                updateSets.add(qCol + ASSIGN + EXCLUDED + DOT + qCol);
            } else {
                String pn = P_UPDATE + "p" + (i++);
                updateSets.add(qCol + ASSIGN + PARAM_PREFIX + pn);
                params.put(pn, coerceParam(table, entry.getKey(), entry.getValue()));
            }
        }
        if (updateSets.isEmpty()) return DO_NOTHING;
        String targetRef = table.contains(".") ? table.substring(table.lastIndexOf('.') + 1) : table;
        StringBuilder usingWhere = new StringBuilder();
        appendRls(usingWhere, table, dialect.quoteIdentifier(targetRef), RlsOp.UPDATE, params);
        return DO_UPDATE + SET + String.join(COMMA_SEP, updateSets) + (usingWhere.isEmpty() ? "" : WHERE + usingWhere);
    }

    public CompiledResult compileUpdate(String table, Map<String, Object> input, List<FilterSpec> filters) {
        if (filters == null || filters.isEmpty()) throw new IllegalArgumentException("Update requires at least one filter");
        requireKnownFilterColumns(table, filters);
        String quotedTable = resolveTable(table);
        Map<String, Object> params = new LinkedHashMap<>();
        List<String> setClauses = new ArrayList<>();
        int i = 0;
        for (var entry : writable(table, RlsOp.UPDATE, input).entrySet()) {
            String pn = P_UPDATE + (i++);
            setClauses.add(dialect.quoteIdentifier(entry.getKey()) + ASSIGN + PARAM_PREFIX + pn);
            params.put(pn, coerceParam(table, entry.getKey(), entry.getValue()));
        }
        if (setClauses.isEmpty()) throw new IllegalArgumentException("Update sets no column");
        StringBuilder where = buildWhere(filters, P_WHERE_FILTER, params, table);
        // Rows the caller may update, and read: the update filter carries the select filter.
        appendRls(where, table, null, RlsOp.UPDATE, params);
        String cte = WITH + CTE_UPD + AS_OPEN + UPDATE + quotedTable
                + SET + String.join(COMMA_SEP, setClauses) + WHERE + where + RETURNING_ALL;
        return new CompiledResult(cte + SPACE + output(table, CTE_UPD, true, List.of(RlsOp.UPDATE), params), params);
    }

    public CompiledResult compileDelete(String table, List<FilterSpec> filters) {
        if (filters == null || filters.isEmpty()) throw new IllegalArgumentException("Delete requires at least one filter");
        requireKnownFilterColumns(table, filters);
        String quotedTable = resolveTable(table);
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder where = buildWhere(filters, P_DELETE_FILTER, params, table);
        // Rows the caller may delete, and read: the delete filter carries the select filter.
        appendRls(where, table, null, RlsOp.DELETE, params);
        String cte = WITH + CTE_DEL + AS_OPEN + DELETE_FROM + quotedTable + WHERE + where + RETURNING_ALL;
        return new CompiledResult(cte + SPACE + output(table, CTE_DEL, true, List.of(), params), params);
    }

    /**
     * {@code row} as it will be written: only columns the caller may set for {@code op}, then the
     * permission's presets over it. Without permissions an unknown column is skipped, as before; with
     * them it is refused, so a client cannot write a column by naming it.
     */
    private Map<String, Object> writable(String table, RlsOp op, Map<String, Object> row) {
        Set<String> settable = access.settableColumns(table, op, schemaInfo);
        Map<String, Object> written = new LinkedHashMap<>();
        for (var entry : row.entrySet()) {
            if (settable.contains(entry.getKey())) {
                written.put(entry.getKey(), entry.getValue());
            } else if (access.enforced()) {
                throw new IllegalArgumentException("Unknown column '" + entry.getKey() + "' in "
                        + op.name().toLowerCase(Locale.ROOT) + " of " + table);
            }
        }
        WriteGuard guard = RlsContext.writeGuard();
        if (guard != null) {
            written.putAll(guard.presets(table, op));
        }
        return written;
    }

    /**
     * What a write returns: the written rows passing the select filter, with the caller's columns
     * only. When any written row fails its permission check the statement raises, so the whole write
     * rolls back.
     */
    private String output(String table, String cte, boolean many, List<RlsOp> checks, Map<String, Object> params) {
        StringBuilder readable = new StringBuilder();
        appendRls(readable, table, cte, RlsOp.SELECT, params);
        String rows = SELECT + starFor(table, cte) + FROM + cte + (readable.isEmpty() ? "" : WHERE + readable);
        String json = many ? dialect.coalesceArray(dialect.aggregateArray(dialect.rowToJson(OUT))) : dialect.rowToJson(OUT);
        String result = SELECT + json + FROM + parens(rows) + SPACE + OUT;
        List<String> violations = violations(table, cte, checks, params);
        if (violations.isEmpty()) {
            return result;
        }
        return SELECT + "CASE WHEN " + PermissionCheckFailedException.raiseWhen(String.join(OR, violations))
                + " IS NULL THEN (" + result + ") END";
    }

    private List<String> violations(String table, String cte, List<RlsOp> checks, Map<String, Object> params) {
        WriteGuard guard = RlsContext.writeGuard();
        List<String> violations = new ArrayList<>();
        if (guard == null) return violations;
        for (RlsOp op : checks) {
            String rowAlias = CHECK_ALIAS + violations.size();
            RlsWhereContributor.Contribution check = guard.check(table, rowAlias, op);
            if (check != null) {
                params.putAll(check.params());
                violations.add("EXISTS (" + SELECT + "1" + FROM + cte + SPACE + rowAlias
                        + WHERE + parens(check.sql()) + " IS NOT TRUE)");
            }
        }
        return violations;
    }

    /** Every column of the caller's view of {@code table}, or {@code alias.*} without permissions. */
    private String starFor(String table, String alias) {
        if (!access.enforced()) {
            return alias + DOT_STAR;
        }
        return String.join(COMMA_SEP, schemaInfo.getColumns(table).stream()
                .map(column -> alias + DOT + dialect.quoteIdentifier(column)).toList());
    }

    /** {@code LIMIT n} for the caller's own row limit on an embedded table, or nothing. */
    private String roleLimit(String table) {
        Integer limit = access.rowLimit(table);
        return limit == null ? "" : LIMIT + limit;
    }

    /** The select's rows capped by the caller's own row limit on {@code table}. */
    private int capped(String table, int limit) {
        Integer roleLimit = access.rowLimit(table);
        return roleLimit == null ? limit : Math.min(limit, roleLimit);
    }

    /**
     * A count needs the role's aggregation right. With permissions, a column outside the caller's view
     * is refused wherever it could steer the query or be selected: filtering, ordering or paging by a
     * column the role cannot read would reveal it.
     */
    private void requirePermittedSelect(SelectQuery query, Set<String> knownCols) {
        if (query.includeCount() && !access.allowsAggregations(query.table())) {
            throw new IllegalArgumentException("Counting rows of " + query.table() + " is not permitted");
        }
        if (!access.enforced()) return;
        List<String> named = new ArrayList<>(query.columns().stream().filter(column -> !STAR.equals(column)).toList());
        query.filters().forEach(filter -> named.add(filter.column()));
        if (query.orderBy() != null) query.orderBy().forEach(order -> named.add(order.column()));
        if (query.orConditions() != null) {
            query.orConditions().forEach(or -> or.conditions().forEach(filter -> named.add(filter.column())));
        }
        if (query.afterCursor() != null && query.orderColumn() != null) named.add(query.orderColumn());
        named.stream().filter(column -> !knownCols.contains(column)).findFirst().ifPresent(column -> {
            throw new IllegalArgumentException("Unknown column '" + column + "' on " + query.table());
        });
    }

    private void requireKnownFilterColumns(String table, List<FilterSpec> filters) {
        if (!access.enforced()) return;
        Set<String> knownCols = schemaInfo.getColumns(table);
        filters.stream().map(FilterSpec::column).filter(column -> !knownCols.contains(column)).findFirst()
                .ifPresent(column -> {
                    throw new IllegalArgumentException("Unknown column '" + column + "' on " + table);
                });
    }

    private String resolveTable(String table) {
        String schema = schemaInfo.getTableSchema(table);
        if (schema == null) schema = defaultSchema;
        String raw = table.contains(DOT) ? table.substring(table.indexOf(DOT) + 1) : table;
        return dialect.qualifiedTable(schema, raw);
    }

    private StringBuilder buildWhere(List<FilterSpec> filters, String prefix, Map<String, Object> params, String table) {
        StringBuilder where = new StringBuilder();
        int fc = 0;
        for (FilterSpec filter : filters) {
            if (!where.isEmpty()) where.append(AND);
            where.append(buildFilterSql(filter, prefix + (fc++), params, table));
        }
        return where;
    }

    /**
     * Splices the active request's RLS predicate for {@code (table, op)} into the
     * REST query's WHERE — the same enforcement the GraphQL path gets via
     * {@code FilterBuilder}. Without this, engine RLS policies (owner/relationship/
     * JSON/claim) would be silently bypassed on REST. {@code alias} is the outer
     * table's alias so relationship/EXISTS predicates correlate correctly (the
     * inner SELECT aliases the table; UPDATE/DELETE reference it by name → null).
     * A {@code null} contributor (no RLS context, e.g. unit tests) is a no-op.
     */
    private void appendRls(StringBuilder where, String table, String alias, RlsOp op, Map<String, Object> params) {
        if (table == null) return;
        RlsWhereContributor contributor = RlsContext.current();
        if (contributor == null) return;
        RlsWhereContributor.Contribution rls = contributor.contribute(table, alias, op);
        if (rls == null || rls.sql() == null || rls.sql().isBlank()) return;
        params.putAll(rls.params());
        if (!where.isEmpty()) where.append(AND);
        where.append("(").append(rls.sql()).append(")");
    }

    private void appendOrConditions(StringBuilder where, List<OrCondition> ors, Set<String> knownCols, Map<String, Object> params, String table) {
        if (ors == null) return;
        for (int oi = 0; oi < ors.size(); oi++) {
            List<String> parts = new ArrayList<>();
            for (int ci = 0; ci < ors.get(oi).conditions().size(); ci++) {
                var filter = ors.get(oi).conditions().get(ci);
                if (knownCols.contains(filter.column()))
                    parts.add(buildFilterSql(filter, P_OR + oi + UNDERSCORE + ci, params, table));
            }
            if (!parts.isEmpty()) {
                if (!where.isEmpty()) where.append(AND);
                where.append(parens(String.join(OR, parts)));
            }
        }
    }

    private StringBuilder buildInnerSelect(String table, String quotedTable, StringBuilder where, StringBuilder orderSql,
                                           int limit, int offset) {
        StringBuilder inner = new StringBuilder();
        inner.append(SELECT).append(access.enforced() ? starFor(table, ALIAS) : STAR)
                .append(FROM).append(quotedTable).append(SPACE).append(ALIAS);
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
            for (OrderBySpec orderSpec : orderBy) {
                String part = ALIAS + DOT + dialect.quoteIdentifier(orderSpec.column()) + SPACE + validateDirection(orderSpec.direction());
                if (orderSpec.nulls() != null) part += SPACE + validateNulls(orderSpec.nulls());
                parts.add(part);
            }
            sql.append(String.join(COMMA_SEP, parts));
        }
        return sql;
    }

    /**
     * Defense-in-depth: validate direction at SQL assembly even though parsers
     * sanitize input. Protects against direct OrderBySpec construction.
     */
    private static String validateDirection(String direction) {
        if ("ASC".equalsIgnoreCase(direction)) return "ASC";
        if ("DESC".equalsIgnoreCase(direction)) return "DESC";
        throw new IllegalArgumentException("Invalid ORDER BY direction: " + direction);
    }

    private static String validateNulls(String nulls) {
        if ("NULLS FIRST".equalsIgnoreCase(nulls)) return "NULLS FIRST";
        if ("NULLS LAST".equalsIgnoreCase(nulls)) return "NULLS LAST";
        throw new IllegalArgumentException("Invalid ORDER BY NULLS clause: " + nulls);
    }

    private String buildJsonAgg(String table, List<String> columns, List<String> embedSql, Set<String> knownCols) {
        // The inner select holds only the caller's columns, so the whole-row form is safe.
        if (columns.isEmpty() && embedSql.isEmpty()) {
            return dialect.coalesceArray(dialect.aggregateArray(dialect.rowToJson(ALIAS)));
        }
        List<String> entries = jsonEntries(columns.isEmpty() ? schemaInfo.getColumns(table) : columns, ALIAS);
        entries.addAll(embedSql);
        return dialect.coalesceArray(dialect.aggregateArray(dialect.buildObject(entries)));
    }

    /**
     * Keyset-pagination predicate ({@code orderColumn > afterCursor}). Skipped when
     * the cursor/column is absent, unknown, or masked — a masked column must not be
     * usable as a cursor (that would leak its ordering).
     */
    private void appendAfterCursor(StringBuilder where, SelectQuery query,
                                   Set<String> knownCols, Map<String, Object> params) {
        String col = query.orderColumn();
        if (query.afterCursor() == null || col == null) return;
        if (!knownCols.contains(col)) return;
        if (!where.isEmpty()) where.append(AND);
        params.put(P_AFTER, convertValue(query.afterCursor(), query.table(), col));
        where.append(dialect.quoteIdentifier(col)).append(GT).append(PARAM_PREFIX).append(P_AFTER);
    }

    /** JSON object key/value entries for {@code cols} read from {@code alias}. */
    private List<String> jsonEntries(Collection<String> cols, String alias) {
        List<String> entries = new ArrayList<>();
        for (String col : cols) {
            entries.add(sqlString(col) + COMMA_SEP + alias + DOT + dialect.quoteIdentifier(col));
        }
        return entries;
    }

    private void appendCountSubquery(StringBuilder sql, List<FilterSpec> filters, String quotedTable, Map<String, Object> params, String table) {
        StringBuilder cw = buildWhere(filters, P_FILTER_COUNT, params, table);
        // totalCount must respect RLS or it leaks the unfiltered total. The count
        // table is unaliased, so relationship/EXISTS correlate by table name (null).
        appendRls(cw, table, null, RlsOp.SELECT, params);
        sql.append(COMMA_SEP).append(parens(SELECT + COUNT_ALL + FROM + quotedTable + (cw.isEmpty() ? "" : WHERE + cw))).append(AS_TOTAL_COUNT);
    }


    private List<String> buildEmbedEntries(String table, List<EmbedSpec> embeds, Map<String, Object> params) {
        return buildEmbedEntries(table, embeds, ALIAS, new AtomicInteger(), params);
    }

    private List<String> buildEmbedEntries(String table, List<EmbedSpec> embeds, String parentAlias,
                                           AtomicInteger counter, Map<String, Object> params) {
        if (embeds == null || embeds.isEmpty()) return List.of();
        List<String> entries = new ArrayList<>();
        for (EmbedSpec embed : embeds) {
            String ia = ALIAS_R + counter.getAndIncrement();
            String oa = ALIAS_R + counter.getAndIncrement();
            var fwd = findForwardFk(table, embed.relationName(), embed.fkHint());
            if (fwd != null) entries.add(buildForwardEmbed(embed, fwd, ia, oa, parentAlias, counter, params));
            else {
                var rev = findReverseFk(table, embed.relationName(), embed.fkHint());
                if (rev != null) entries.add(buildReverseEmbed(embed, rev, ia, oa, parentAlias, counter, params));
            }
        }
        return entries;
    }

    private String buildForwardEmbed(EmbedSpec embed, SchemaInfo.FkInfo fk, String ia, String oa,
                                     String parentAlias, AtomicInteger counter, Map<String, Object> params) {
        String refTable = resolveTable(fk.refTable());
        List<String> childEntries = buildEmbedEntries(fk.refTable(), embed.children(), oa, counter, params);
        String innerSel = childEntries.isEmpty() ? buildEmbedSelect(embed, ia, fk.refTable())
                : SELECT + starFor(fk.refTable(), ia);
        String rowExpr = buildRowExpr(embed, oa, fk.refTable(), childEntries);
        // RLS on the embedded related table (aliased ia) — without this, an embed
        // (?select=*,fk(*)) would expose related rows the caller may not read.
        StringBuilder ew = new StringBuilder(ia + DOT + dialect.quoteIdentifier(fk.refColumn())
            + ASSIGN + parentAlias + DOT + dialect.quoteIdentifier(fk.fkColumn()));
        appendRls(ew, fk.refTable(), ia, RlsOp.SELECT, params);
        return sqlString(embed.relationName()) + COMMA_SEP + parens(
            SELECT + rowExpr + FROM
            + parens(innerSel + FROM + refTable + SPACE + ia + WHERE + ew)
            + SPACE + oa);
    }

    private String buildReverseEmbed(EmbedSpec embed, SchemaInfo.ReverseFkInfo rev, String ia, String oa,
                                     String parentAlias, AtomicInteger counter, Map<String, Object> params) {
        String childTable = resolveTable(rev.childTable());
        List<String> childEntries = buildEmbedEntries(rev.childTable(), embed.children(), oa, counter, params);
        String innerSel = childEntries.isEmpty() ? buildEmbedSelect(embed, ia, rev.childTable())
                : SELECT + starFor(rev.childTable(), ia);
        String rowExpr = buildRowExpr(embed, oa, rev.childTable(), childEntries);
        // RLS on the embedded child table (aliased ia) — same leak guard as forward.
        StringBuilder ew = new StringBuilder(ia + DOT + dialect.quoteIdentifier(rev.fkColumn())
            + ASSIGN + parentAlias + DOT + dialect.quoteIdentifier(rev.refColumns().get(0)));
        appendRls(ew, rev.childTable(), ia, RlsOp.SELECT, params);
        return sqlString(embed.relationName()) + COMMA_SEP + COALESCE + parens(
            parens(SELECT + FN_JSON_AGG + parens(rowExpr) + FROM
            + parens(innerSel + FROM + childTable + SPACE + ia + WHERE + ew + roleLimit(rev.childTable()))
            + SPACE + oa) + COMMA_SEP + EMPTY_JSON_ARRAY);
    }

    /**
     * Build the row expression for an embed. When there are no children, use rowToJson for
     * simplicity. When there are children, build an explicit jsonb_build_object that includes
     * both the requested columns and the child embed entries.
     */
    private String buildRowExpr(EmbedSpec embed, String outerAlias, String table, List<String> childEntries) {
        // The embed's inner select holds only the caller's columns, so the whole-row form is safe.
        if (childEntries.isEmpty()) {
            return dialect.rowToJson(outerAlias + DOT_STAR);
        }
        List<String> cols = embed.columns().isEmpty() || embed.columns().contains(STAR)
            ? new ArrayList<>(schemaInfo.getColumns(table))
            : embed.columns();
        List<String> entries = jsonEntries(cols, outerAlias);
        entries.addAll(childEntries);
        return dialect.buildObject(entries);
    }

    private String buildEmbedSelect(EmbedSpec embed, String alias, String table) {
        if (embed.columns().isEmpty() || embed.columns().contains(STAR)) return SELECT + starFor(table, alias);
        Set<String> known = new HashSet<>(schemaInfo.getColumns(table));
        if (access.enforced()) {
            embed.columns().stream().filter(column -> !known.contains(column)).findFirst().ifPresent(column -> {
                throw new IllegalArgumentException("Unknown column '" + column + "' on " + table);
            });
        }
        List<String> safe = embed.columns().stream().filter(known::contains).map(c -> alias + DOT + dialect.quoteIdentifier(c)).toList();
        return safe.isEmpty() ? SELECT + starFor(table, alias) : SELECT + String.join(COMMA_SEP, safe);
    }

    private SchemaInfo.FkInfo findForwardFk(String table, String relName, String fkHint) {
        String tablePrefix = table + DOT;
        for (var e : schemaInfo.getAllForwardFks().entrySet()) {
            SchemaInfo.FkInfo fk = e.getValue();
            if (matchesFk(e.getKey(), tablePrefix, fk.fkColumn(), fkHint, fk.refTable(), relName)) {
                return fk;
            }
        }
        return null;
    }

    private SchemaInfo.ReverseFkInfo findReverseFk(String table, String relName, String fkHint) {
        String tablePrefix = table + DOT;
        for (var e : schemaInfo.getAllReverseFks().entrySet()) {
            SchemaInfo.ReverseFkInfo rfk = e.getValue();
            if (matchesFk(e.getKey(), tablePrefix, rfk.fkColumn(), fkHint, rfk.childTable(), relName)) {
                return rfk;
            }
        }
        return null;
    }

    /** True if the FK entry belongs to {@code tablePrefix}, matches the optional {@code fkHint}, and its target table equals {@code relName}. */
    private static boolean matchesFk(String entryKey, String tablePrefix,
                                      String fkColumn, String fkHint,
                                      String targetTable, String relName) {
        if (!entryKey.startsWith(tablePrefix)) return false;
        if (fkHint != null && !fkColumn.equals(fkHint)) return false;
        String raw = targetTable.contains(DOT) ? targetTable.substring(targetTable.indexOf(DOT) + 1) : targetTable;
        return raw.equals(relName);
    }


    private String buildFilterSql(FilterSpec filter, String paramName, Map<String, Object> params, String table) {
        String colRef = dialect.quoteIdentifier(filter.column());
        String neg = filter.negated() ? NOT : "";
        String op = filter.operator();

        return switch (op) {
            case "eq", "neq", "gt", "gte", "lt", "lte" ->
                    comparisonSql(op, filter, paramName, params, colRef, neg, table);
            case "like", "ilike", "startswith", "endswith", "match", "imatch" ->
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
                params.put(paramName, convertValue(filter.value(), table, filter.column()));
                yield neg + FN_ARRAY_LENGTH + parens(colRef + COMMA_SEP + "1") + ASSIGN + PARAM_PREFIX + paramName;
            }
            case "fts", "plfts", "phfts", "wfts" ->
                    tsFilter(params, paramName, filter, neg, colRef, ftsFunction(op));
            case "sl", "sr", "nxl", "nxr", "adj" ->
                    rangeSql(op, filter, paramName, params, colRef, neg, table);
            default -> throw new IllegalArgumentException("Unsupported filter operator: " + op);
        };
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
            case "match" -> stringFilter(params, paramName, neg, colRef, REGEX_MATCH, filter.value());
            case "imatch" -> stringFilter(params, paramName, neg, colRef, REGEX_IMATCH, filter.value());
            default -> throw new IllegalStateException(op);
        };
    }

    private String nullSql(String op, FilterSpec filter, String paramName,
                            Map<String, Object> params, String colRef, String neg, String table) {
        return switch (op) {
            case "is" -> isFilter(neg, colRef, filter.value());
            case "isnotnull" -> neg + colRef + IS_NOT_NULL;
            case "isdistinct" -> {
                params.put(paramName, convertValue(filter.value(), table, filter.column()));
                yield neg + colRef + IS_DISTINCT_FROM + PARAM_PREFIX + paramName;
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
        String expr = switch (op) {
            case "haskey" -> FN_JSONB_EXISTS + parens(colRef + COMMA_SEP + PARAM_PREFIX + paramName);
            case "jsoncontains", "contains", "cs" -> colRef + CONTAINS + PARAM_PREFIX + paramName + CAST_JSONB;
            case "jsoncontained", "containedin", "cd" -> colRef + CONTAINED_BY + PARAM_PREFIX + paramName + CAST_JSONB;
            case "jsonpath", "jsonpathexists" -> FN_JSONB_PATH_EXISTS + parens(colRef + COMMA_SEP + PARAM_PREFIX + paramName + CAST_JSONPATH);
            case "arraycontains", "arrayhasall" -> colRef + CONTAINS + PARAM_PREFIX + paramName;
            case "arrayhasany", "ov" -> colRef + OVERLAPS + PARAM_PREFIX + paramName;
            default -> throw new IllegalStateException(op);
        };
        return jsonFilter(params, paramName, filter, neg, expr, table);
    }

    private String ftsFunction(String op) {
        return switch (op) {
            case "fts" -> FN_TO_TSQUERY;
            case "plfts" -> FN_PLAINTO_TSQUERY;
            case "phfts" -> FN_PHRASETO_TSQUERY;
            case "wfts" -> FN_WEBSEARCH_TSQUERY;
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
        params.put(pn, convertValue(filter.value(), table, filter.column()));
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
        params.put(pn, convertValue(filter.value(), table, filter.column()));
        return neg + expr;
    }

    private String tsFilter(Map<String, Object> params, String pn, FilterSpec filter, String neg, String col, String functionName) {
        params.put(pn, filter.value());
        return neg + FN_TO_TSVECTOR + parens(col) + MATCH_TSQUERY + functionName + parens(PARAM_PREFIX + pn);
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
            params.put(param, convertValue(items[i].trim(), table, filter.column()));
            ph.add(PARAM_PREFIX + param);
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
                String json = (value instanceof String stringValue) ? stringValue : toJsonString(value);
                yield new SqlParameterValue(Types.OTHER, json);
            }
            default -> value;
        };
    }

    private String toJsonString(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException _) {
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
        } catch (NumberFormatException _) {
            return value;
        }
    }
}
