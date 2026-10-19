package io.github.excalibase.rest.compiler;

import io.github.excalibase.ExactNumbers;
import io.github.excalibase.compiler.KeyedWrite;
import io.github.excalibase.compiler.KeyedWrites;
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

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static io.github.excalibase.compiler.SqlKeywords.*;

public class RestQueryCompiler {

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
    private final ParamValues values;
    private final RestFilterSql filterSql;

    /** {@code write} runs a write on a database without RETURNING in place of {@code sql}. */
    public record CompiledResult(String sql, Map<String, Object> params, KeyedWrite write) {
        public CompiledResult(String sql, Map<String, Object> params) {
            this(sql, params, null);
        }
    }
    public record FilterSpec(String column, String operator, String value, boolean negated) {}
    public record OrderBySpec(String column, String direction, String nulls) {}
    /** Filters and nested groups joined by AND when {@code and}, else by OR; negated as a whole when {@code negated}. */
    public record LogicGroup(boolean and, boolean negated, List<FilterSpec> filters, List<LogicGroup> groups) {
        /** Every filter in the group, nested groups included. */
        public List<FilterSpec> allFilters() {
            List<FilterSpec> all = new ArrayList<>(filters);
            groups.forEach(group -> all.addAll(group.allFilters()));
            return all;
        }
    }

    /**
     * One top-level logic parameter ({@code or}, {@code and}, {@code not.or}, {@code not.and}); {@code conditions}
     * are all of its filters, nested ones included.
     */
    public record OrCondition(List<FilterSpec> conditions, LogicGroup group) {
        public OrCondition(List<FilterSpec> conditions) {
            this(conditions, new LogicGroup(false, false, conditions, List.of()));
        }

        public OrCondition(LogicGroup group) {
            this(group.allFilters(), group);
        }
    }
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
        this.values = new ParamValues(schemaInfo, dialect);
        this.filterSql = new RestFilterSql(dialect, values);
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
        if (!dialect.supportsFunctions()) {
            throw new IllegalArgumentException("Functions are not supported on " + dialect.databaseName());
        }
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
        requireKnownFilterColumns(query.table(), query.filters());
        if (query.orConditions() != null) {
            query.orConditions().forEach(or -> requireKnownFilterColumns(query.table(), or.conditions()));
        }
        List<FilterSpec> allFilters = query.filters();

        // k-NN search is not a predicate — it modifies ORDER BY + LIMIT — so the vector
        // FilterSpec must not land in buildWhere. Only the first vector filter is honored.
        List<FilterSpec> filters = new ArrayList<>(allFilters.size());
        VectorSearchBuilder.VectorClause vectorClause = splitVectorFilter(allFilters, filters, params);

        StringBuilder where = buildWhere(filters, P_FILTER, params, query.table());
        appendOrConditions(where, query.orConditions(), params, query.table());
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
            filterSql.requireSupported(filter);
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
        Map<String, Object> bound = new LinkedHashMap<>();
        String insert = insertSql(table, quotedTable, row, params, bound);
        if (keyedWrites()) {
            var keyed = keyed(table, quotedTable, List.of(RlsOp.INSERT));
            return new CompiledResult(insert, params, keyed.insert(List.of(new KeyedWrites.Insert(insert, params, bound)), false));
        }
        String cte = WITH + CTE_INS + AS_OPEN + insert + RETURNING_ALL;
        return new CompiledResult(cte + SPACE + output(table, CTE_INS, false, List.of(RlsOp.INSERT), params), params);
    }

    /** {@code INSERT INTO t(cols) VALUES (...)} of {@code row}, its values bound in {@code params} and {@code bound}. */
    private String insertSql(String table, String quotedTable, Map<String, Object> row, Map<String, Object> params,
                             Map<String, Object> bound) {
        List<String> cols = new ArrayList<>();
        List<String> vals = new ArrayList<>();
        int i = 0;
        for (var entry : row.entrySet()) {
            String pn = P_INSERT + (i++);
            cols.add(dialect.quoteIdentifier(entry.getKey()));
            vals.add(PARAM_PREFIX + pn);
            params.put(pn, values.coerceParam(table, entry.getKey(), entry.getValue()));
            bound.put(entry.getKey(), params.get(pn));
        }
        return INSERT_INTO + quotedTable + parens(joinCols(cols)) + VALUES + parens(String.join(COMMA_SEP, vals));
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
        if (keyedWrites()) {
            return keyedBulkInsert(table, quotedTable, written, colNames);
        }

        List<String> valueRows = new ArrayList<>();
        for (int rowIndex = 0; rowIndex < written.size(); rowIndex++) {
            List<String> vals = new ArrayList<>();
            for (String col : colNames) {
                String pn = P_INSERT + rowIndex + UNDERSCORE + col;
                params.put(pn, values.coerceParam(table, col, written.get(rowIndex).getOrDefault(col, null)));
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

    /** One statement per row, so each row's generated key is known; a column a row omits is NULL, as in one VALUES. */
    private CompiledResult keyedBulkInsert(String table, String quotedTable, List<Map<String, Object>> written,
                                           List<String> colNames) {
        List<KeyedWrites.Insert> inserts = new ArrayList<>();
        Map<String, Object> allParams = new LinkedHashMap<>();
        for (Map<String, Object> row : written) {
            Map<String, Object> full = new LinkedHashMap<>();
            colNames.forEach(col -> full.put(col, row.get(col)));
            Map<String, Object> params = new LinkedHashMap<>();
            Map<String, Object> bound = new LinkedHashMap<>();
            inserts.add(new KeyedWrites.Insert(insertSql(table, quotedTable, full, params, bound), params, bound));
            allParams.putAll(params);
        }
        var keyed = keyed(table, quotedTable, List.of(RlsOp.INSERT));
        return new CompiledResult(inserts.getFirst().sql(), allParams, keyed.insert(inserts, true));
    }

    /**
     * INSERT … ON CONFLICT DO UPDATE: the insert half obeys the insert permission, the update half the
     * update permission's columns, presets and filter, and every written row must pass both checks.
     */
    public CompiledResult compileUpsert(String table, Map<String, Object> input, List<String> conflictCols) {
        String quotedTable = resolveTable(table);
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> row = writable(table, RlsOp.INSERT, input);
        if (keyedWrites()) {
            return keyedUpsert(table, quotedTable, input, row, conflictCols);
        }
        String insert = insertSql(table, quotedTable, row, params, new LinkedHashMap<>());
        String onConflict = ON_CONFLICT + parens(String.join(COMMA_SEP,
                conflictCols.stream().map(dialect::quoteIdentifier).toList()))
                + doUpdate(table, input, conflictCols, params);
        String cte = WITH + CTE_INS + AS_OPEN + insert + onConflict + RETURNING_ALL;
        return new CompiledResult(cte + SPACE
                + output(table, CTE_INS, false, List.of(RlsOp.INSERT, RlsOp.UPDATE), params), params);
    }

    /**
     * An INSERT when no row holds the sent key (the primary key, or on_conflict's columns), else an UPDATE of
     * that row alone: {@code ON DUPLICATE KEY UPDATE} would update whichever row any unique key collides with.
     * Without every key column sent no existing row can conflict on the key, so it is a plain insert. An existing row the update permission's filter hides is left
     * as it is, as Postgres's {@code DO UPDATE … WHERE} does.
     */
    private CompiledResult keyedUpsert(String table, String quotedTable, Map<String, Object> input,
                                       Map<String, Object> row, List<String> conflictCols) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> bound = new LinkedHashMap<>();
        String insert = insertSql(table, quotedTable, row, params, bound);
        var keyed = keyed(table, quotedTable, List.of(RlsOp.INSERT, RlsOp.UPDATE));
        List<String> keys = rowKey(table);
        boolean onPrimaryKey = new HashSet<>(conflictCols).equals(new HashSet<>(keys));
        List<String> conflict = onPrimaryKey ? keys : conflictCols;
        if (conflict.isEmpty() || !conflict.stream().allMatch(key -> bound.get(key) != null)) {
            return new CompiledResult(insert, params, keyed.insert(List.of(new KeyedWrites.Insert(insert, params, bound)), false));
        }
        Map<String, Object> updateParams = new LinkedHashMap<>();
        AtomicInteger sent = new AtomicInteger();
        List<String> sets = upsertSets(table, input, conflictCols, updateParams, column -> {
            String name = P_UPDATE + "s" + sent.getAndIncrement();
            updateParams.put(name, bound.get(column));
            return PARAM_PREFIX + name;
        });
        KeyedWrites.Insert written = new KeyedWrites.Insert(insert, params, bound);
        KeyedWrites.Condition set = null;
        KeyedWrites.Condition updatable = null;
        if (!sets.isEmpty()) {
            Map<String, Object> filterParams = new LinkedHashMap<>();
            StringBuilder filter = new StringBuilder();
            appendRls(filter, table, ALIAS, RlsOp.UPDATE, filterParams);
            set = new KeyedWrites.Condition(String.join(COMMA_SEP, sets), updateParams);
            updatable = new KeyedWrites.Condition(filter.toString(), filterParams);
        }
        return new CompiledResult(insert, params, onPrimaryKey ? keyed.upsert(written, set, updatable)
                : keyed.upsertOn(conflict, written, set, updatable));
    }

    /**
     * The DO UPDATE half. It can overwrite a pre-existing row, so it reaches only rows the caller may
     * update; columns are qualified with the target relation name because a bare column in
     * {@code ON CONFLICT … WHERE} is ambiguous with {@code EXCLUDED}.
     */
    private String doUpdate(String table, Map<String, Object> input, List<String> conflictCols,
                            Map<String, Object> params) {
        List<String> updateSets = upsertSets(table, input, conflictCols, params,
                column -> EXCLUDED + DOT + dialect.quoteIdentifier(column));
        if (updateSets.isEmpty()) return DO_NOTHING;
        String targetRef = table.contains(".") ? table.substring(table.lastIndexOf('.') + 1) : table;
        StringBuilder usingWhere = new StringBuilder();
        appendRls(usingWhere, table, dialect.quoteIdentifier(targetRef), RlsOp.UPDATE, params);
        return DO_UPDATE + SET + String.join(COMMA_SEP, updateSets) + (usingWhere.isEmpty() ? "" : WHERE + usingWhere);
    }

    /** The upsert's update assignments: a sent column takes {@code sentValue} of it, a preset its own value. */
    private List<String> upsertSets(String table, Map<String, Object> input, List<String> conflictCols,
                                    Map<String, Object> params, UnaryOperator<String> sentValue) {
        Map<String, Object> changed = new LinkedHashMap<>(input);
        conflictCols.forEach(changed::remove);
        Map<String, Object> updated = writable(table, RlsOp.UPDATE, changed);
        List<String> updateSets = new ArrayList<>();
        int i = 0;
        for (var entry : updated.entrySet()) {
            String qCol = dialect.quoteIdentifier(entry.getKey());
            if (changed.containsKey(entry.getKey())) {
                updateSets.add(qCol + ASSIGN + sentValue.apply(entry.getKey()));
            } else {
                String pn = P_UPDATE + "p" + (i++);
                updateSets.add(qCol + ASSIGN + PARAM_PREFIX + pn);
                params.put(pn, values.coerceParam(table, entry.getKey(), entry.getValue()));
            }
        }
        return updateSets;
    }

    public CompiledResult compileUpdate(String table, Map<String, Object> input, List<FilterSpec> filters) {
        if (filters == null || filters.isEmpty()) throw new IllegalArgumentException("Update requires at least one filter");
        requireKnownFilterColumns(table, filters);
        String quotedTable = resolveTable(table);
        Map<String, Object> params = new LinkedHashMap<>();
        List<String> setClauses = new ArrayList<>();
        Map<String, Object> set = new LinkedHashMap<>();
        int i = 0;
        for (var entry : writable(table, RlsOp.UPDATE, input).entrySet()) {
            String pn = P_UPDATE + (i++);
            setClauses.add(dialect.quoteIdentifier(entry.getKey()) + ASSIGN + PARAM_PREFIX + pn);
            params.put(pn, values.coerceParam(table, entry.getKey(), entry.getValue()));
            set.put(entry.getKey(), params.get(pn));
        }
        if (setClauses.isEmpty()) throw new IllegalArgumentException("Update sets no column");
        StringBuilder where = buildWhere(filters, P_WHERE_FILTER, params, table);
        // Rows the caller may update, and read: the update filter carries the select filter.
        appendRls(where, table, writtenAlias(), RlsOp.UPDATE, params);
        String update = UPDATE + quotedTable + aliasClause() + SET + String.join(COMMA_SEP, setClauses) + WHERE + where;
        if (keyedWrites()) {
            var keyed = keyed(table, quotedTable, List.of(RlsOp.UPDATE));
            return new CompiledResult(update, params, keyed.update(update, where.toString(), params, set));
        }
        String cte = WITH + CTE_UPD + AS_OPEN + update + RETURNING_ALL;
        return new CompiledResult(cte + SPACE + output(table, CTE_UPD, true, List.of(RlsOp.UPDATE), params), params);
    }

    public CompiledResult compileDelete(String table, List<FilterSpec> filters) {
        if (filters == null || filters.isEmpty()) throw new IllegalArgumentException("Delete requires at least one filter");
        requireKnownFilterColumns(table, filters);
        String quotedTable = resolveTable(table);
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder where = buildWhere(filters, P_DELETE_FILTER, params, table);
        // Rows the caller may delete, and read: the delete filter carries the select filter.
        appendRls(where, table, writtenAlias(), RlsOp.DELETE, params);
        String delete = DELETE_FROM + quotedTable + aliasClause() + WHERE + where;
        if (keyedWrites()) {
            var keyed = keyed(table, quotedTable, List.of());
            return new CompiledResult(delete, params, keyed.delete(delete, where.toString(), params));
        }
        String cte = WITH + CTE_DEL + AS_OPEN + delete + RETURNING_ALL;
        return new CompiledResult(cte + SPACE + output(table, CTE_DEL, true, List.of(), params), params);
    }

    /**
     * How an UPDATE or DELETE names its table: keyed writes also read it, locked, under {@link #ALIAS}, so
     * their filter is written over that alias; otherwise over the bare table name.
     */
    private String writtenAlias() {
        return keyedWrites() ? ALIAS : null;
    }

    private String aliasClause() {
        return keyedWrites() ? SPACE + ALIAS : "";
    }

    /** No RETURNING: the write runs as statements whose rows are read back by key. */
    private boolean keyedWrites() {
        return !dialect.supportsReturning();
    }

    /**
     * How {@code table}'s written rows are read back: the caller's columns, passing the select filter, or none
     * when the caller may not select it; and the permission's {@code checks} every written row must pass.
     */
    private KeyedWrites keyed(String table, String quotedTable, List<RlsOp> checks) {
        WriteGuard guard = RlsContext.writeGuard();
        List<String> passes = new ArrayList<>();
        Map<String, Object> checkParams = new LinkedHashMap<>();
        for (RlsOp op : guard == null ? List.<RlsOp>of() : checks) {
            RlsWhereContributor.Contribution check = guard.check(table, ALIAS, op);
            if (check != null) {
                passes.add(parens(check.sql()));
                checkParams.putAll(check.params());
            }
        }
        for (RlsOp op : guard == null ? List.<RlsOp>of() : checks) {
            if (guard.checkReads(table, op).contains(table)) {
                throw new IllegalArgumentException("The permission check on " + table + " reads the table it writes, so"
                        + " it cannot be enforced on " + dialect.databaseName());
            }
        }
        KeyedWrites.Condition check = passes.isEmpty() ? null
                : new KeyedWrites.Condition(String.join(AND, passes), checkParams);
        KeyedWrites.ReadBack readBack = null;
        if (access.permits(table, RlsOp.SELECT)) {
            Map<String, Object> params = new LinkedHashMap<>();
            StringBuilder readable = new StringBuilder();
            appendRls(readable, table, ALIAS, RlsOp.SELECT, params);
            readBack = new KeyedWrites.ReadBack(dialect.buildObject(jsonEntries(table, schemaInfo.getColumns(table), ALIAS)),
                    readable.toString(), params);
        }
        List<String> keys = rowKey(table);
        WriteGuard writeGuard = RlsContext.writeGuard();
        boolean generated = writeGuard != null ? writeGuard.keyGenerated(table)
                : keys.size() == 1 && schemaInfo.isAutoIncrementColumn(table, keys.getFirst());
        return new KeyedWrites(dialect, new KeyedWrites.Target(table, quotedTable, ALIAS, keys, generated), readBack, check);
    }

    /** The database's primary key, whether or not the caller may read it. */
    private List<String> rowKey(String table) {
        WriteGuard guard = RlsContext.writeGuard();
        if (guard != null) return guard.rowKey(table);
        return schemaInfo.hasPrimaryKey(table) ? schemaInfo.getPrimaryKeys(table) : List.of();
    }

    /**
     * {@code row} as it will be written: only columns the caller may set for {@code op}, then the
     * permission's presets over it. Without permissions an unknown column is skipped, as before; with
     * them it is refused (see {@link TableAccess#requireSettable}), so a client cannot write a column by
     * naming it.
     */
    private Map<String, Object> writable(String table, RlsOp op, Map<String, Object> row) {
        if (access.enforced()) {
            access.requireSettable(table, op, row.keySet(), schemaInfo);
        }
        Set<String> settable = access.settableColumns(table, op, schemaInfo);
        Map<String, Object> written = new LinkedHashMap<>();
        for (var entry : row.entrySet()) {
            if (settable.contains(entry.getKey())) {
                written.put(entry.getKey(), entry.getValue());
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
     * only, or none when the caller may not select the table. When any written row fails its permission
     * check the statement raises, so the whole write rolls back.
     */
    private String output(String table, String cte, boolean many, List<RlsOp> checks, Map<String, Object> params) {
        String rows;
        if (access.permits(table, RlsOp.SELECT)) {
            StringBuilder readable = new StringBuilder();
            appendRls(readable, table, cte, RlsOp.SELECT, params);
            rows = SELECT + representedColumns(table, cte) + FROM + cte + (readable.isEmpty() ? "" : WHERE + readable);
        } else {
            // Insert without select: the rows are written, none is represented.
            rows = SELECT + "NULL" + FROM + cte + WHERE + "FALSE";
        }
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

    /** The written rows' columns in their answered form, for the representation built from them whole. */
    private String representedColumns(String table, String alias) {
        if (!hasExactNumbers(table)) {
            return starFor(table, alias);
        }
        return String.join(COMMA_SEP, schemaInfo.getColumns(table).stream()
                .map(column -> jsonValue(table, alias, column) + " AS " + dialect.quoteIdentifier(column)).toList());
    }

    /**
     * Whether the row can be written as JSON whole: not where a column is answered in another form than the
     * database writes it, as bigints and decimals are (strings of every digit).
     */
    private boolean wholeRowJson(String table) {
        return dialect.supportsRowToJson() && !hasExactNumbers(table);
    }

    private boolean hasExactNumbers(String table) {
        return schemaInfo.getColumns(table).stream().map(column -> schemaInfo.getColumnType(table, column))
                .anyMatch(type -> ExactNumbers.isExact(type) || ExactNumbers.isExactArray(type));
    }

    private String jsonValue(String table, String alias, String column) {
        return dialect.jsonValue(alias + DOT + dialect.quoteIdentifier(column), schemaInfo.getColumnType(table, column));
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

    /** A filter on a column the table lacks is refused, with or without permissions: a typo must not match every row. */
    private void requireKnownFilterColumns(String table, List<FilterSpec> filters) {
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
            where.append(filterSql.sql(filter, prefix + (fc++), params, table));
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

    private void appendOrConditions(StringBuilder where, List<OrCondition> ors, Map<String, Object> params, String table) {
        if (ors == null) return;
        for (int oi = 0; oi < ors.size(); oi++) {
            String group = logicSql(ors.get(oi).group(), P_OR + oi, params, table);
            if (!group.isEmpty()) {
                if (!where.isEmpty()) where.append(AND);
                where.append(group);
            }
        }
    }

    /** One logic group as a parenthesised predicate, or empty when it holds nothing. */
    private String logicSql(LogicGroup group, String prefix, Map<String, Object> params, String table) {
        List<String> parts = new ArrayList<>();
        for (int ci = 0; ci < group.filters().size(); ci++) {
            parts.add(filterSql.sql(group.filters().get(ci), prefix + UNDERSCORE + ci, params, table));
        }
        for (int gi = 0; gi < group.groups().size(); gi++) {
            String nested = logicSql(group.groups().get(gi), prefix + "g" + gi, params, table);
            if (!nested.isEmpty()) parts.add(nested);
        }
        if (parts.isEmpty()) return "";
        String joined = parens(String.join(group.and() ? AND : OR, parts));
        return group.negated() ? NOT + joined : joined;
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
                String colRef = ALIAS + DOT + dialect.quoteIdentifier(orderSpec.column());
                String direction = validateDirection(orderSpec.direction());
                parts.add(orderSpec.nulls() == null ? colRef + SPACE + direction
                        : dialect.orderByNulls(colRef, direction, validateNulls(orderSpec.nulls())));
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

    /** The NULLS position, FIRST or LAST. */
    private static String validateNulls(String nulls) {
        if ("NULLS FIRST".equalsIgnoreCase(nulls)) return "FIRST";
        if ("NULLS LAST".equalsIgnoreCase(nulls)) return "LAST";
        throw new IllegalArgumentException("Invalid ORDER BY NULLS clause: " + nulls);
    }

    private String buildJsonAgg(String table, List<String> columns, List<String> embedSql, Set<String> knownCols) {
        // The inner select holds only the caller's columns, so the whole-row form is safe.
        if (columns.isEmpty() && embedSql.isEmpty() && wholeRowJson(table)) {
            return dialect.coalesceArray(dialect.aggregateArray(dialect.rowToJson(ALIAS)));
        }
        List<String> entries = jsonEntries(table, columns.isEmpty() ? schemaInfo.getColumns(table) : columns, ALIAS);
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
        params.put(P_AFTER, values.convertValue(query.afterCursor(), query.table(), col));
        where.append(dialect.quoteIdentifier(col)).append(GT).append(PARAM_PREFIX).append(P_AFTER);
    }

    /** JSON object key/value entries for {@code cols} of {@code table} read from {@code alias}. */
    private List<String> jsonEntries(String table, Collection<String> cols, String alias) {
        List<String> entries = new ArrayList<>();
        for (String col : cols) {
            entries.add(sqlString(col) + COMMA_SEP + jsonValue(table, alias, col));
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
        String children = parens(innerSel + FROM + childTable + SPACE + ia + WHERE + ew + roleLimit(rev.childTable()))
                + SPACE + oa;
        if (!dialect.supportsRowToJson()) {
            return sqlString(embed.relationName()) + COMMA_SEP
                    + parens(SELECT + dialect.coalesceArray(dialect.aggregateArray(rowExpr)) + FROM + children);
        }
        return sqlString(embed.relationName()) + COMMA_SEP + COALESCE + parens(
            parens(SELECT + FN_JSON_AGG + parens(rowExpr) + FROM + children) + COMMA_SEP + EMPTY_JSON_ARRAY);
    }

    /**
     * Build the row expression for an embed. When there are no children, use rowToJson for
     * simplicity. When there are children, build an explicit jsonb_build_object that includes
     * both the requested columns and the child embed entries.
     */
    private String buildRowExpr(EmbedSpec embed, String outerAlias, String table, List<String> childEntries) {
        // The embed's inner select holds only the caller's columns, so the whole-row form is safe.
        if (childEntries.isEmpty() && wholeRowJson(table)) {
            return dialect.rowToJson(outerAlias + DOT_STAR);
        }
        if (childEntries.isEmpty()) {
            return dialect.buildObject(jsonEntries(table, embedColumns(embed, table), outerAlias));
        }
        List<String> cols = embed.columns().isEmpty() || embed.columns().contains(STAR)
            ? new ArrayList<>(schemaInfo.getColumns(table))
            : embed.columns();
        List<String> entries = jsonEntries(table, cols, outerAlias);
        entries.addAll(childEntries);
        return dialect.buildObject(entries);
    }

    /** The columns {@link #buildEmbedSelect} selects. */
    private List<String> embedColumns(EmbedSpec embed, String table) {
        Set<String> known = schemaInfo.getColumns(table);
        List<String> named = embed.columns().contains(STAR) ? List.of()
                : embed.columns().stream().filter(known::contains).toList();
        return named.isEmpty() ? new ArrayList<>(known) : named;
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
}
