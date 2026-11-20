package io.github.excalibase.sqlite;

import graphql.language.Argument;
import graphql.language.Field;
import io.github.excalibase.compiler.KeyedWrites;
import io.github.excalibase.compiler.MutationBuilder;
import io.github.excalibase.compiler.SqlCompiler;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.security.RlsContext;
import io.github.excalibase.security.RlsOp;
import io.github.excalibase.security.RlsWhereContributor;
import io.github.excalibase.security.WriteGuard;
import io.github.excalibase.spi.MutationCompiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

import static io.github.excalibase.compiler.SqlKeywords.AND;
import static io.github.excalibase.compiler.SqlKeywords.DEFAULT;
import static io.github.excalibase.compiler.SqlKeywords.INSERT_INTO;
import static io.github.excalibase.compiler.SqlKeywords.P_UPDATE;
import static io.github.excalibase.compiler.SqlKeywords.VALUES;
import static io.github.excalibase.compiler.SqlKeywords.WHERE;
import static io.github.excalibase.compiler.SqlKeywords.joinCols;
import static io.github.excalibase.compiler.SqlKeywords.parens;
import static io.github.excalibase.schema.GraphqlConstants.ARG_INPUT;
import static io.github.excalibase.schema.GraphqlConstants.CREATE_MANY_PREFIX;
import static io.github.excalibase.schema.GraphqlConstants.CREATE_PREFIX;
import static io.github.excalibase.schema.GraphqlConstants.DELETE_PREFIX;
import static io.github.excalibase.schema.GraphqlConstants.UPDATE_PREFIX;

/**
 * SQLite mutation compiler. SQLite can {@code RETURNING}, but a write there is judged against the permission's check
 * after it runs, so rows are written and read back by primary key through
 * {@link KeyedWrites}, which REST uses too: inserts by the keys sent or generated, updates by the keys they
 * locked before writing, deletes read locked before they go.
 */
public class SqliteMutationCompiler implements MutationCompiler {

    private static final Pattern QUOTED_IDENTIFIER = Pattern.compile("\"(?:[^\"]|\"\")*\"");

    @Override
    public SqlCompiler.CompiledQuery compileMutation(Field field, String fieldName,
                                                     Map<String, Object> params, Map<String, Object> variables,
                                                     MutationBuilder shared) {
        SqliteWrite write = compileWrite(field, fieldName, params, variables, shared);
        return write == null ? null : SqlCompiler.CompiledQuery.twoPhase(write, params);
    }

    private SqliteWrite compileWrite(Field field, String fieldName, Map<String, Object> params,
                                    Map<String, Object> variables, MutationBuilder shared) {
        if (fieldName.startsWith(CREATE_MANY_PREFIX)) {
            String tableName = shared.resolveMutationTable(fieldName.substring(CREATE_MANY_PREFIX.length()), fieldName);
            if (tableName != null) {
                return compileInsert(tableName,
                        shared.bulkInsertParts(field, tableName, params, variables, false), params, true, shared);
            }
        } else if (fieldName.startsWith(CREATE_PREFIX)) {
            String tableName = shared.resolveMutationTable(fieldName.substring(CREATE_PREFIX.length()), fieldName);
            if (tableName != null) {
                return compileSingleInsert(field, tableName, params, variables, shared);
            }
        } else if (fieldName.startsWith(UPDATE_PREFIX)) {
            String tableName = shared.resolveMutationTable(fieldName.substring(UPDATE_PREFIX.length()), fieldName);
            if (tableName != null) return compileUpdate(field, tableName, params, variables, shared);
        } else if (fieldName.startsWith(DELETE_PREFIX)) {
            String tableName = shared.resolveMutationTable(fieldName.substring(DELETE_PREFIX.length()), fieldName);
            if (tableName != null) return compileDelete(field, tableName, params, shared);
        }
        return null;
    }

    private SqliteWrite compileSingleInsert(Field field, String tableName,
                                           Map<String, Object> params, Map<String, Object> variables,
                                           MutationBuilder shared) {
        Argument inputArg = shared.findArg(field, ARG_INPUT);
        if (inputArg == null) return null;
        Map<String, Object> inputFields = shared.extractObjectFields(inputArg.getValue(), variables);
        MutationBuilder.BulkInsertParts row = shared.bulkInsertParts(field, tableName, List.of(inputFields),
                params, false);
        return compileInsert(tableName, row, params, false, shared);
    }

    private SqliteWrite compileInsert(String tableName, MutationBuilder.BulkInsertParts rows,
                                     Map<String, Object> params, boolean many, MutationBuilder shared) {
        if (rows == null) return null;
        String table = shared.qualifiedTable(tableName);
        List<KeyedWrites.Insert> inserts = new ArrayList<>();
        List<String> columns = QUOTED_IDENTIFIER.matcher(rows.columns()).results().map(MatchResult::group).toList();
        for (int row = 0; row < rows.rowCount(); row++) {
            inserts.add(new KeyedWrites.Insert(insertRow(table, columns, rows.rowValues().get(row)), params,
                    rows.boundRows().get(row)));
        }
        KeyedWrites keyed = keyed(tableName, table, rows.alias(), rows.objectSql(), RlsOp.INSERT, params, shared);
        // A caller who may not read the table gets the affected_rows object, once, as on Postgres.
        return new SqliteWrite(tableName, checkReads(tableName, RlsOp.INSERT),
                keyed.insert(inserts, many && shared.returnsRows(tableName)));
    }

    /**
     * One row's INSERT with the columns it sets. SQLite has no {@code DEFAULT} in a VALUES list, so a column the row
     * leaves out is left out of the statement and takes its default as it would in a single insert.
     */
    private static String insertRow(String table, List<String> columns, String rowValues) {
        List<String> values = List.of(rowValues.substring(1, rowValues.length() - 1).split(", "));
        List<String> setColumns = new ArrayList<>();
        List<String> setValues = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            if (!DEFAULT.equals(values.get(i))) {
                setColumns.add(columns.get(i));
                setValues.add(values.get(i));
            }
        }
        if (setColumns.isEmpty()) {
            return INSERT_INTO + table + " DEFAULT VALUES";
        }
        return INSERT_INTO + table + parens(joinCols(setColumns)) + VALUES + parens(joinCols(setValues));
    }

    private SqliteWrite compileUpdate(Field field, String tableName,
                                     Map<String, Object> params, Map<String, Object> variables,
                                     MutationBuilder shared) {
        Argument inputArg = shared.findArg(field, ARG_INPUT);
        if (inputArg == null) return null;

        Map<String, Object> inputFields = shared.extractObjectFields(inputArg.getValue(), variables);
        MutationBuilder.UpdateParts update = shared.updateParts(field, tableName, inputFields, P_UPDATE, params, false);
        if (update.setClauses().isEmpty()) return null;

        StringBuilder whereSql = new StringBuilder();
        shared.filterBuilder().applyWhere(whereSql, field, update.alias(), params, tableName, RlsOp.UPDATE);
        if (whereSql.isEmpty()) return null; // Require where to prevent accidental full-table update

        String table = shared.qualifiedTable(tableName);
        String dml = shared.dialect().cteUpdate(update.alias(), table, joinCols(update.setClauses()),
                whereSql.toString(), update.objectSql());
        Map<String, Object> set = new LinkedHashMap<>();
        MutationBuilder.withPresets(inputFields, shared.presets(tableName, RlsOp.UPDATE))
                .forEach((column, value) -> set.put(column, shared.bindValue(tableName, column, value)));
        KeyedWrites keyed = keyed(tableName, table, update.alias(), update.objectSql(), RlsOp.UPDATE, params, shared);
        return new SqliteWrite(tableName, checkReads(tableName, RlsOp.UPDATE),
                keyed.update(dml, condition(whereSql), params, set));
    }

    private SqliteWrite compileDelete(Field field, String tableName,
                                     Map<String, Object> params, MutationBuilder shared) {
        String alias = shared.dialect().randAlias();
        String objectSql = shared.queryBuilder().buildObject(field.getSelectionSet(), tableName, alias, params);

        StringBuilder whereSql = new StringBuilder();
        shared.filterBuilder().applyWhere(whereSql, field, alias, params, tableName, RlsOp.DELETE);
        if (whereSql.isEmpty()) return null;

        String table = shared.qualifiedTable(tableName);
        String dml = shared.dialect().cteDelete(alias, table, whereSql.toString(), objectSql);
        KeyedWrites keyed = keyed(tableName, table, alias, objectSql, null, params, shared);
        return new SqliteWrite(tableName, Set.of(), keyed.delete(dml, condition(whereSql), params));
    }

    /**
     * Rows are read back by primary key as the caller reads them, passing its select filter; none when the
     * caller may not read the table. Every written row must pass the permission's check for {@code operation}.
     */
    private static KeyedWrites keyed(String tableName, String table, String alias, String objectSql, RlsOp operation,
                                     Map<String, Object> params, MutationBuilder shared) {
        KeyedWrites.ReadBack readBack = operation == RlsOp.INSERT && !shared.returnsRows(tableName)
                ? KeyedWrites.ReadBack.aggregate(objectSql, params) : null;
        if (shared.returnsRows(tableName)) {
            List<String> readable = new ArrayList<>();
            shared.filterBuilder().appendRlsConditions(readable, tableName, alias, params, RlsOp.SELECT);
            readBack = new KeyedWrites.ReadBack(objectSql, String.join(AND, readable), params);
        }
        WriteGuard guard = RlsContext.writeGuard();
        KeyedWrites.Condition check = null;
        if (guard != null && operation != null) {
            RlsWhereContributor.Contribution contribution = guard.check(tableName, alias, operation);
            check = contribution == null ? null : new KeyedWrites.Condition(contribution.sql(), contribution.params());
        }
        return new KeyedWrites(shared.dialect(), target(tableName, table, alias, guard, shared), readBack, check);
    }

    /** The database's primary key, whether or not the caller may read it, and whether SQLite generates it. */
    private static KeyedWrites.Target target(String tableName, String table, String alias, WriteGuard guard,
                                             MutationBuilder shared) {
        if (guard != null) {
            return new KeyedWrites.Target(tableName, table, alias, guard.rowKey(tableName), guard.keyGenerated(tableName));
        }
        SchemaInfo schema = shared.schemaInfo();
        List<String> keys = schema.hasPrimaryKey(tableName) ? schema.getPrimaryKeys(tableName) : List.of();
        boolean generated = keys.size() == 1 && schema.isAutoIncrementColumn(tableName, keys.getFirst());
        return new KeyedWrites.Target(tableName, table, alias, keys, generated);
    }

    private static Set<String> checkReads(String tableName, RlsOp operation) {
        WriteGuard guard = RlsContext.writeGuard();
        return guard == null ? Set.of() : guard.checkReads(tableName, operation);
    }

    /** The filter of a {@code WHERE} clause the filter builder wrote, without the keyword. */
    private static String condition(StringBuilder whereSql) {
        String where = whereSql.toString();
        return where.startsWith(WHERE) ? where.substring(WHERE.length()) : where;
    }
}
