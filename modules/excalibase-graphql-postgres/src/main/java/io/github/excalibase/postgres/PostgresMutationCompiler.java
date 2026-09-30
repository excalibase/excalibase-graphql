package io.github.excalibase.postgres;

import graphql.language.*;
import io.github.excalibase.compiler.MutationBuilder;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.security.PermissionCheckFailedException;
import io.github.excalibase.security.RlsContext;
import io.github.excalibase.security.RlsOp;
import io.github.excalibase.security.RlsWhereContributor;
import io.github.excalibase.security.WriteGuard;
import io.github.excalibase.spi.MutationCompiler;
import io.github.excalibase.compiler.SqlCompiler;

import java.util.*;
import static io.github.excalibase.schema.GraphqlConstants.*;
import static io.github.excalibase.compiler.SqlKeywords.*;

/**
 * PostgreSQL mutation compiler. Uses CTE + RETURNING pattern.
 */
public class PostgresMutationCompiler implements MutationCompiler {

    public PostgresMutationCompiler() {
        // Stateless compiler — no initialization needed
    }

    @Override
    public SqlCompiler.CompiledQuery compileMutation(Field field, String fieldName,
                                                     Map<String, Object> params, Map<String, Object> variables,
                                                     MutationBuilder shared) {
        String sql = routeMutation(field, fieldName, params, variables, shared);
        if (sql == null) return null;
        return new SqlCompiler.CompiledQuery(shared.dialect().wrapMutationResult(sql, fieldName), params);
    }

    @Override
    public SqlCompiler.CompiledQuery compileMutationFragment(Field field, String fieldName,
                                                             Map<String, Object> params, Map<String, Object> variables,
                                                             MutationBuilder shared) {
        String sql = routeMutation(field, fieldName, params, variables, shared);
        if (sql == null) return null;
        // Return raw CTE SQL without wrapping — SqlCompiler combines multiple fragments
        return new SqlCompiler.CompiledQuery(sql, params);
    }

    private String routeMutation(Field field, String fieldName,
                                 Map<String, Object> params, Map<String, Object> variables,
                                 MutationBuilder shared) {
        // Collection-suffix routes must be checked before plain-prefix routes
        // because e.g. updateFooCollection also starts with UPDATE_PREFIX.
        String sql = tryRoute(UPDATE_PREFIX, COLLECTION_SUFFIX, fieldName,
                (f, t) -> compileUpdateCollection(f, t, params, variables, shared), field, shared);
        if (sql != null) return sql;
        sql = tryRoute(DELETE_FROM_PREFIX, COLLECTION_SUFFIX, fieldName,
                (f, t) -> compileDeleteFromCollection(f, t, params, variables, shared), field, shared);
        if (sql != null) return sql;
        sql = tryRoute(CREATE_MANY_PREFIX, "", fieldName,
                (f, t) -> compileBulkInsert(f, t, params, variables, shared), field, shared);
        if (sql != null) return sql;
        sql = tryRoute(CREATE_PREFIX, "", fieldName,
                (f, t) -> compileInsert(f, t, params, variables, shared), field, shared);
        if (sql != null) return sql;
        sql = tryRoute(UPDATE_PREFIX, "", fieldName,
                (f, t) -> compileUpdate(f, t, params, variables, shared), field, shared);
        if (sql != null) return sql;
        return tryRoute(DELETE_PREFIX, "", fieldName,
                (f, t) -> compileDelete(f, t, params, shared), field, shared);
    }

    /**
     * If {@code fieldName} matches {@code prefix + <type> + suffix}, resolves
     * the type to a table name and invokes {@code fn}. Returns the compiled
     * SQL, or {@code null} if the name does not match this route or the type
     * cannot be resolved.
     */
    private String tryRoute(String prefix, String suffix, String fieldName,
                            RouteCompiler fn, Field field, MutationBuilder shared) {
        if (!fieldName.startsWith(prefix)) return null;
        if (!suffix.isEmpty() && !fieldName.endsWith(suffix)) return null;
        String typePart = fieldName.substring(prefix.length(), fieldName.length() - suffix.length());
        String tableName = shared.resolveMutationTable(typePart, fieldName);
        return tableName != null ? fn.compile(field, tableName) : null;
    }

    @FunctionalInterface
    private interface RouteCompiler {
        String compile(Field field, String tableName);
    }

    String compileInsert(Field field, String tableName, Map<String, Object> params,
                         Map<String, Object> variables, MutationBuilder shared) {
        Argument inputArg = shared.findArg(field, ARG_INPUT);
        if (inputArg == null) return null;

        String alias = shared.dialect().randAlias();
        String objectSql = shared.resultObject(field, tableName, alias, params);

        Map<String, List<Map<String, Object>>> nestedInserts = new LinkedHashMap<>();
        Map<String, Object> row = insertRow(inputArg, tableName, variables, shared, nestedInserts);
        shared.requireSettable(tableName, RlsOp.INSERT, row.keySet());
        row = MutationBuilder.withPresets(row, shared.presets(tableName, RlsOp.INSERT));

        List<String> cols = new ArrayList<>();
        List<String> vals = new ArrayList<>();
        for (var entry : row.entrySet()) {
            cols.add(shared.dialect().quoteIdentifier(entry.getKey()));
            String paramName = namedParam(P_INSERT, entry.getKey(), params.size());
            vals.add(param(paramName) + shared.getEnumCastForMutation(tableName, entry.getKey()));
            params.put(paramName, shared.convertCompositeValue(tableName, entry.getKey(), entry.getValue()));
        }

        String onConflictSql = parseOnConflict(field, shared, params, tableName);
        List<WrittenRows> written = new ArrayList<>();
        written.add(new WrittenRows(alias, tableName, onConflictSql.isEmpty()
                ? List.of(RlsOp.INSERT) : List.of(RlsOp.INSERT, RlsOp.UPDATE)));
        String parentCte = shared.dialect().cteInsert(alias, shared.qualifiedTable(tableName),
                joinCols(cols), joinCols(vals), onConflictSql, objectSql);
        String sql = nestedInserts.isEmpty() ? parentCte
                : buildNestedInsertCte(parentCte, alias, tableName, nestedInserts, params, shared, written);
        return guardedResult(sql, alias, tableName, written, params, shared);
    }

    /**
     * The input's column values; nested FK inserts (a relationship's object carrying a {@code data}
     * array) are collected into {@code nestedInserts} instead, each child row being its own write. Any
     * other name is a column, refused later when the caller may not set it.
     */
    private Map<String, Object> insertRow(Argument inputArg, String tableName, Map<String, Object> variables,
                                          MutationBuilder shared, Map<String, List<Map<String, Object>>> nestedInserts) {
        if (!(inputArg.getValue() instanceof ObjectValue inputOv)) {
            return shared.extractObjectFields(inputArg.getValue(), variables);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        for (ObjectField of : inputOv.getObjectFields()) {
            boolean relationship = shared.schemaInfo().getReverseFk(tableName, of.getName()) != null;
            Optional<List<Map<String, Object>>> nestedRows = relationship
                    ? extractNestedData(of.getValue(), variables, shared) : Optional.empty();
            if (nestedRows.isPresent()) {
                nestedInserts.put(of.getName(), nestedRows.get());
            } else {
                row.put(of.getName(), MutationBuilder.extractValue(of.getValue(), variables));
            }
        }
        return row;
    }

    /**
     * Returns the data rows if this Value is a nested insert pattern (an ObjectValue
     * carrying a "data" ArrayValue), or Optional.empty() if the Value is a plain
     * scalar/column argument.
     */
    private Optional<List<Map<String, Object>>> extractNestedData(Value<?> value, Map<String, Object> variables,
                                                                    MutationBuilder shared) {
        if (!(value instanceof ObjectValue ov)) return Optional.empty();
        ObjectField dataField = ov.getObjectFields().stream()
                .filter(f -> "data".equals(f.getName())).findFirst().orElse(null);
        if (dataField == null || !(dataField.getValue() instanceof ArrayValue av)) return Optional.empty();
        return Optional.of(av.getValues().stream()
                .map(v -> shared.extractObjectFields(v, variables))
                .toList());
    }

    /**
     * Builds a CTE chain: parent INSERT + one child INSERT CTE per nested FK relationship.
     * Child rows SELECT the parent PK from the parent CTE alias, other columns from params.
     */
    private String buildNestedInsertCte(String parentCte, String alias, String tableName,
                                         Map<String, List<Map<String, Object>>> nestedInserts,
                                         Map<String, Object> params, MutationBuilder shared,
                                         List<WrittenRows> written) {
        // Split at ") SELECT " to get CTE body and final SELECT
        int splitIdx = parentCte.lastIndexOf(") SELECT ");
        if (splitIdx == -1) return parentCte;
        String parentCtePart = parentCte.substring(0, splitIdx + 1); // WITH "alias" AS (... RETURNING *)
        String finalSelect = parentCte.substring(splitIdx + 2);       // SELECT objectSql FROM "alias"

        List<String> cteParts = new ArrayList<>();
        cteParts.add(parentCtePart);

        for (var nested : nestedInserts.entrySet()) {
            String childCte = buildChildInsertCte(nested.getKey(), nested.getValue(), alias, tableName, params,
                    shared, written);
            if (childCte != null) cteParts.add(childCte);
        }

        if (cteParts.size() == 1) return parentCte;
        return String.join(", ", cteParts) + " " + finalSelect;
    }

    /**
     * Build a single child-table INSERT CTE for a nested-FK insert, or null if not applicable. Each
     * child row obeys the child table's own insert permission: its columns, presets and check.
     */
    private String buildChildInsertCte(String nestedFieldName, List<Map<String, Object>> rows,
                                        String alias, String tableName,
                                        Map<String, Object> params, MutationBuilder shared,
                                        List<WrittenRows> written) {
        if (rows.isEmpty()) return null;

        SchemaInfo.ReverseFkInfo revFk = shared.schemaInfo().getReverseFk(tableName, nestedFieldName);
        if (revFk == null) return null;

        String childTable = revFk.childTable();
        if (!shared.access().permits(childTable, RlsOp.INSERT)) {
            throw new IllegalArgumentException("Unknown field '" + nestedFieldName + "' in the input of "
                    + tableName);
        }
        rows.forEach(row -> shared.requireSettable(childTable, RlsOp.INSERT, row.keySet()));
        Map<String, Object> presets = shared.presets(childTable, RlsOp.INSERT);
        String fkCol = revFk.fkColumn();           // column in child table (e.g. order_id)
        String refCol = revFk.refColumns().get(0); // column in parent table (e.g. order_id)

        String childAlias = shared.dialect().randAlias();
        String qualifiedChild = shared.qualifiedTable(childTable);

        // Columns: FK col from parent, then data columns from first row
        List<String> childCols = new ArrayList<>();
        childCols.add(shared.dialect().quoteIdentifier(fkCol));
        List<String> dataCols = new ArrayList<>(rows.get(0).keySet());
        dataCols.addAll(presets.keySet());
        dataCols.forEach(col -> childCols.add(shared.dialect().quoteIdentifier(col)));

        // One SELECT row per data row, joined with UNION ALL
        List<String> selectRows = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = MutationBuilder.withPresets(rows.get(i), presets);
            List<String> rowVals = new ArrayList<>();
            rowVals.add(alias + DOT + shared.dialect().quoteIdentifier(refCol));
            for (String col : dataCols) {
                String paramName = namedParam(P_NESTED_INSERT, col + "_" + i, params.size());
                // Cast + convert each child value exactly like the top-level insert.
                // The child CTE is INSERT ... SELECT, where a bare bind param is
                // typed as varchar — without the cast, a uuid/enum/jsonb/etc. child
                // column rejects it ("is of type X but expression is of type
                // character varying").
                String enumCast = shared.getEnumCastForMutation(childTable, col);
                params.put(paramName, shared.convertCompositeValue(childTable, col, row.get(col)));
                rowVals.add(param(paramName) + enumCast);
            }
            selectRows.add(SELECT + joinCols(rowVals) + FROM + alias);
        }

        written.add(new WrittenRows(childAlias, childTable, List.of(RlsOp.INSERT)));
        return childAlias + AS_OPEN
                + INSERT_INTO + qualifiedChild + " " + parens(joinCols(childCols))
                + " " + String.join(UNION_ALL, selectRows)
                + RETURNING_ALL;
    }

    String compileBulkInsert(Field field, String tableName, Map<String, Object> params,
                             Map<String, Object> variables, MutationBuilder shared) {
        MutationBuilder.BulkInsertParts bulk = shared.bulkInsertParts(field, tableName, params, variables, true);
        if (bulk == null) return null;
        String sql = shared.dialect().cteBulkInsert(bulk.alias(), shared.qualifiedTable(tableName),
                bulk.columns(), bulk.valueRows(), bulk.objectSql());
        if (!shared.returnsRows(tableName)) {
            sql = countingWrittenRows(sql, bulk.alias(), bulk.objectSql());
        }
        return guardedResult(sql, bulk.alias(), tableName,
                List.of(new WrittenRows(bulk.alias(), tableName, List.of(RlsOp.INSERT))), params, shared);
    }

    String compileUpdate(Field field, String tableName, Map<String, Object> params,
                         Map<String, Object> variables, MutationBuilder shared) {
        Argument inputArg = shared.findArg(field, ARG_INPUT);
        if (inputArg == null) return null;

        if (!hasWhere(field, shared)) return null;
        Map<String, Object> inputFields = shared.extractObjectFields(inputArg.getValue(), variables);
        if (inputFields.isEmpty()) return null;
        MutationBuilder.UpdateParts update = shared.updateParts(field, tableName, inputFields, P_UPDATE, params, true);
        String alias = update.alias();

        StringBuilder whereSql = new StringBuilder();
        shared.filterBuilder().applyWhere(whereSql, field, alias, params, tableName, RlsOp.UPDATE);

        String sql = shared.dialect().cteUpdate(alias, shared.qualifiedTable(tableName),
                joinCols(update.setClauses()), whereSql.toString(), update.objectSql());
        return guardedResult(sql, alias, tableName,
                List.of(new WrittenRows(alias, tableName, List.of(RlsOp.UPDATE))), params, shared);
    }

    String compileDelete(Field field, String tableName, Map<String, Object> params, MutationBuilder shared) {
        if (!hasWhere(field, shared)) return null;
        String alias = shared.dialect().randAlias();
        String objectSql = shared.queryBuilder().buildObject(field.getSelectionSet(), tableName, alias, params);

        StringBuilder whereSql = new StringBuilder();
        shared.filterBuilder().applyWhere(whereSql, field, alias, params, tableName, RlsOp.DELETE);

        return shared.dialect().cteDelete(alias, shared.qualifiedTable(tableName),
                whereSql.toString(), objectSql);
    }

    String compileUpdateCollection(Field field, String tableName, Map<String, Object> params,
                                   Map<String, Object> variables, MutationBuilder shared) {
        Argument setArg = shared.findArg(field, ARG_SET);
        if (setArg == null) return null;

        Map<String, Object> setFields = shared.extractObjectFields(setArg.getValue(), variables);
        MutationBuilder.UpdateParts update = shared.updateParts(field, tableName, setFields, P_UC_SET, params, true);
        String alias = update.alias();
        String objectSql = update.objectSql();
        List<String> setClauses = update.setClauses();

        int atMost = parseAtMost(field, variables, shared);

        String filterWhere = buildCollectionFilter(field, shared, params, tableName, RlsOp.UPDATE);
        String atMostParam = namedParam(P_UC_AT_MOST, params.size());
        params.put(atMostParam, atMost);

        String sql = shared.dialect().cteUpdate(alias, shared.qualifiedTable(tableName),
                joinCols(setClauses),
                ctidSubquery(shared.qualifiedTable(tableName),
                        shared.dialect().quoteIdentifier(INNER_ALIAS), filterWhere, atMostParam),
                objectSql);
        return guardedResult(sql, alias, tableName,
                List.of(new WrittenRows(alias, tableName, List.of(RlsOp.UPDATE))), params, shared);
    }

    String compileDeleteFromCollection(Field field, String tableName, Map<String, Object> params,
                                       Map<String, Object> variables, MutationBuilder shared) {
        String alias = shared.dialect().randAlias();
        String objectSql = shared.queryBuilder().buildObject(field.getSelectionSet(), tableName, alias, params);

        int atMost = parseAtMost(field, variables, shared);

        String filterWhere = buildCollectionFilter(field, shared, params, tableName, RlsOp.DELETE);
        String atMostParam = namedParam(P_DC_AT_MOST, params.size());
        params.put(atMostParam, atMost);

        return shared.dialect().cteDelete(alias, shared.qualifiedTable(tableName),
                ctidSubquery(shared.qualifiedTable(tableName),
                        shared.dialect().quoteIdentifier(INNER_ALIAS), filterWhere, atMostParam),
                objectSql);
    }

    // === Private helpers ===

    /** The rows one CTE writes, and the permission checks (INSERT, UPDATE or both) they must pass. */
    private record WrittenRows(String cteAlias, String table, List<RlsOp> checks) {}

    /**
     * {@code sql} answering one aggregate over every row the CTE wrote instead of one object per row: an
     * insert the caller may not read back returns {@code { affected_rows }}.
     */
    private static String countingWrittenRows(String sql, String alias, String affectedRowsObject) {
        int split = sql.lastIndexOf(") SELECT ");
        return sql.substring(0, split + 1) + " " + SELECT + affectedRowsObject + FROM + alias;
    }

    /**
     * Finishes a mutation statement: what it returns is limited to rows passing the select filter (the
     * role's columns are already all its type holds), and when any written row fails its permission
     * check the statement raises, so the whole mutation rolls back. A caller who may not read the
     * table gets the count of every written row, never a row.
     */
    private String guardedResult(String sql, String outputAlias, String tableName, List<WrittenRows> written,
                                 Map<String, Object> params, MutationBuilder shared) {
        int split = sql.lastIndexOf(") SELECT ");
        if (split == -1) return sql;
        String ctePart = sql.substring(0, split + 1);
        String selectPart = sql.substring(split + 2);
        List<String> readable = new ArrayList<>();
        if (shared.returnsRows(tableName)) {
            shared.filterBuilder().appendRlsConditions(readable, tableName, outputAlias, params, RlsOp.SELECT);
        }
        if (!readable.isEmpty()) {
            selectPart += WHERE + String.join(AND, readable);
        }
        List<String> violations = violations(written, params, shared);
        if (violations.isEmpty()) {
            return ctePart + " " + selectPart;
        }
        String raise = PermissionCheckFailedException.raiseWhen(String.join(" OR ", violations));
        return ctePart + " " + SELECT + "CASE WHEN " + raise + " IS NULL THEN (" + selectPart + ") END";
    }

    private List<String> violations(List<WrittenRows> written, Map<String, Object> params, MutationBuilder shared) {
        WriteGuard guard = RlsContext.writeGuard();
        List<String> violations = new ArrayList<>();
        if (guard == null) return violations;
        for (WrittenRows rows : written) {
            for (RlsOp operation : rows.checks()) {
                String rowAlias = shared.dialect().randAlias();
                RlsWhereContributor.Contribution check = guard.check(rows.table(), rowAlias, operation);
                if (check != null) {
                    params.putAll(check.params());
                    violations.add("EXISTS (" + SELECT + "1" + FROM + rows.cteAlias() + " " + rowAlias
                            + WHERE + "(" + check.sql() + ") IS NOT TRUE)");
                }
            }
        }
        return violations;
    }

    /** An update or delete without a where argument is not compiled: it would reach every permitted row. */
    private static boolean hasWhere(Field field, MutationBuilder shared) {
        return shared.findArg(field, ARG_WHERE) != null || shared.findArg(field, ARG_FILTER) != null;
    }

    private String parseOnConflict(Field field, MutationBuilder shared,
                                   Map<String, Object> params, String tableName) {
        Argument onConflictArg = shared.findArg(field, ARG_ON_CONFLICT);
        if (onConflictArg == null || !(onConflictArg.getValue() instanceof ObjectValue ocOv)) {
            return "";
        }
        String constraint = null;
        List<String> updateCols = new ArrayList<>();
        for (ObjectField of : ocOv.getObjectFields()) {
            if (ON_CONFLICT_CONSTRAINT.equals(of.getName())) {
                constraint = shared.filterBuilder().extractValue(of.getValue()).toString();
            } else if (ON_CONFLICT_UPDATE_COLUMNS.equals(of.getName()) && of.getValue() instanceof ArrayValue av) {
                for (Value<?> elementValue : av.getValues()) {
                    updateCols.add(shared.filterBuilder().extractValue(elementValue).toString());
                }
            }
        }
        if (constraint == null || updateCols.isEmpty()) {
            return "";
        }
        if (!shared.permitsUpsert(tableName)) {
            // DO UPDATE rewrites rows that already exist, so onConflict is part of
            // create only for a caller who also holds UPDATE. For anyone else the
            // argument does not exist — reported as such, never as a denial.
            throw new IllegalArgumentException("Unknown argument 'onConflict' on field '" + field.getName() + "'");
        }
        shared.requireSettable(tableName, RlsOp.UPDATE, updateCols);
        return buildOnConflictClause(shared, params, tableName, constraint, updateCols);
    }

    /**
     * {@code ON CONFLICT … DO UPDATE SET …}, the update presets, and the update filter as the gate: DO
     * UPDATE can overwrite a pre-existing row, so it reaches only rows the caller may update. Columns
     * are qualified with the target relation name because a bare column in
     * {@code ON CONFLICT … WHERE} is ambiguous with {@code EXCLUDED}.
     */
    private String buildOnConflictClause(MutationBuilder shared, Map<String, Object> params,
                                         String tableName, String constraint, List<String> updateCols) {
        String clause = " " + shared.dialect().onConflict(List.of(constraint), updateCols)
                + presetAssignments(shared, params, tableName);
        String targetRef = tableName.contains(".")
                ? tableName.substring(tableName.lastIndexOf('.') + 1) : tableName;
        List<String> usingConds = new ArrayList<>();
        shared.filterBuilder().appendRlsConditions(usingConds, tableName,
                shared.dialect().quoteIdentifier(targetRef), params, RlsOp.UPDATE);
        if (!usingConds.isEmpty()) {
            clause += WHERE + String.join(AND, usingConds);
        }
        return clause;
    }

    private String presetAssignments(MutationBuilder shared, Map<String, Object> params, String tableName) {
        StringBuilder assignments = new StringBuilder();
        shared.presets(tableName, RlsOp.UPDATE).forEach((column, value) -> {
            String paramName = namedParam(P_UPDATE, column, params.size());
            params.put(paramName, value);
            assignments.append(", ").append(assignWithCast(shared.dialect().quoteIdentifier(column), paramName,
                    shared.getEnumCastForMutation(tableName, column)));
        });
        return assignments.toString();
    }

    private int parseAtMost(Field field, Map<String, Object> variables, MutationBuilder shared) {
        Argument atMostArg = shared.findArg(field, ARG_AT_MOST);
        if (atMostArg != null) {
            Object val = MutationBuilder.extractValue(atMostArg.getValue(), variables);
            if (val instanceof Number number) return number.intValue();
        }
        return 1;
    }

    private String buildCollectionFilter(Field field, MutationBuilder shared,
                                         Map<String, Object> params, String tableName, RlsOp op) {
        String innerAlias = shared.dialect().quoteIdentifier(INNER_ALIAS);
        List<String> conditions = new ArrayList<>();
        Argument filterArg = shared.findArg(field, ARG_FILTER);
        if (filterArg != null && filterArg.getValue() instanceof ObjectValue filterOv) {
            shared.filterBuilder().buildFilterConditions(filterOv, innerAlias, params, conditions, tableName);
        }
        // The permission filter applies to bulk-by-filter mutations too, on the inner alias.
        shared.filterBuilder().appendRlsConditions(conditions, tableName, innerAlias, params, op);
        if (!conditions.isEmpty()) {
            return WHERE + String.join(AND, conditions);
        }
        return "";
    }
}
