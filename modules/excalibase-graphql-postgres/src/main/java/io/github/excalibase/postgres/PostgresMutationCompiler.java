package io.github.excalibase.postgres;

import graphql.language.*;
import io.github.excalibase.compiler.InsertRow;
import io.github.excalibase.compiler.InsertRowParser;
import io.github.excalibase.compiler.MutationBuilder;
import io.github.excalibase.compiler.MutationSequence;
import io.github.excalibase.security.PermissionCheckFailedException;
import io.github.excalibase.security.RlsOp;
import io.github.excalibase.spi.MutationCompiler;
import io.github.excalibase.compiler.SqlCompiler;
import io.github.excalibase.postgres.WriteChecks.WrittenRows;

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
    public boolean supportsNestedInserts() {
        return true;
    }

    @Override
    public SqlCompiler.CompiledQuery compileMutation(Field field, String fieldName,
                                                     Map<String, Object> params, Map<String, Object> variables,
                                                     MutationBuilder shared) {
        Optional<MutationSequence> nested = nestedInsert(field, fieldName, params, variables, shared);
        if (nested.isPresent()) return SqlCompiler.CompiledQuery.sequenced(nested.get(), params);
        String sql = routeMutation(field, fieldName, params, variables, shared);
        if (sql == null) return null;
        return new SqlCompiler.CompiledQuery(shared.dialect().wrapMutationResult(sql, fieldName), params);
    }

    @Override
    public SqlCompiler.CompiledQuery compileMutationFragment(Field field, String fieldName,
                                                             Map<String, Object> params, Map<String, Object> variables,
                                                             MutationBuilder shared) {
        Optional<MutationSequence> nested = nestedInsert(field, fieldName, params, variables, shared);
        if (nested.isPresent()) return SqlCompiler.CompiledQuery.sequenced(nested.get(), params);
        String sql = routeMutation(field, fieldName, params, variables, shared);
        if (sql == null) return null;
        // Return raw CTE SQL without wrapping — SqlCompiler combines multiple fragments
        return new SqlCompiler.CompiledQuery(sql, params);
    }

    /**
     * A {@code create} or {@code createMany} some of whose rows nest others through relationships, as the
     * ordered statements Hasura runs for it; empty for any other mutation, which stays one statement.
     */
    private Optional<MutationSequence> nestedInsert(Field field, String fieldName, Map<String, Object> params,
                                                    Map<String, Object> variables, MutationBuilder shared) {
        boolean many = fieldName.startsWith(CREATE_MANY_PREFIX);
        if (!many && !fieldName.startsWith(CREATE_PREFIX)) return Optional.empty();
        String typePart = fieldName.substring((many ? CREATE_MANY_PREFIX : CREATE_PREFIX).length());
        String tableName = shared.resolveMutationTable(typePart, fieldName);
        Argument rowsArg = shared.findArg(field, many ? ARG_INPUTS : ARG_INPUT);
        if (tableName == null || rowsArg == null) return Optional.empty();
        InsertRowParser parser = new InsertRowParser(shared.schemaInfo(), variables);
        List<InsertRow> rows = many ? parser.rows(tableName, rowsArg.getValue())
                : List.of(parser.row(tableName, rowsArg.getValue()));
        if (rows.stream().allMatch(InsertRow::flat)) return Optional.empty();
        String onConflictSql = many ? "" : parseOnConflict(field, shared, params, tableName);
        String responseKey = field.getAlias() != null ? field.getAlias() : fieldName;
        return Optional.of(new MutationSequence(List.of(new PostgresNestedInsert(shared, params)
                .field(field, responseKey, tableName, rows, many, onConflictSql))));
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

        Map<String, Object> row = new InsertRowParser(shared.schemaInfo(), variables)
                .row(tableName, inputArg.getValue()).columns();
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
        List<WrittenRows> written = List.of(new WrittenRows(alias, tableName, onConflictSql.isEmpty()
                ? List.of(RlsOp.INSERT) : List.of(RlsOp.INSERT, RlsOp.UPDATE)));
        String sql = shared.dialect().cteInsert(alias, shared.qualifiedTable(tableName),
                joinCols(cols), joinCols(vals), onConflictSql, objectSql);
        return guardedResult(sql, alias, tableName, written, params, shared);
    }

    String compileBulkInsert(Field field, String tableName, Map<String, Object> params,
                             Map<String, Object> variables, MutationBuilder shared) {
        Argument inputsArg = shared.findArg(field, ARG_INPUTS);
        if (inputsArg == null) return null;
        List<Map<String, Object>> rows = new InsertRowParser(shared.schemaInfo(), variables)
                .rows(tableName, inputsArg.getValue()).stream().map(InsertRow::columns).toList();
        MutationBuilder.BulkInsertParts bulk = shared.bulkInsertParts(field, tableName, rows, params, true);
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
        List<String> violations = WriteChecks.violations(written, params, shared);
        if (violations.isEmpty()) {
            return ctePart + " " + selectPart;
        }
        String raise = PermissionCheckFailedException.raiseWhen(String.join(" OR ", violations));
        return ctePart + " " + SELECT + "CASE WHEN " + raise + " IS NULL THEN (" + selectPart + ") END";
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
