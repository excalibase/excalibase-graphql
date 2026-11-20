package io.github.excalibase.mysql;

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
import io.github.excalibase.spi.MutationKind;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.excalibase.compiler.SqlKeywords.AND;
import static io.github.excalibase.compiler.SqlKeywords.INSERT_INTO;
import static io.github.excalibase.compiler.SqlKeywords.P_UPDATE;
import static io.github.excalibase.compiler.SqlKeywords.VALUES;
import static io.github.excalibase.compiler.SqlKeywords.WHERE;
import static io.github.excalibase.compiler.SqlKeywords.joinCols;
import static io.github.excalibase.compiler.SqlKeywords.parens;
import static io.github.excalibase.schema.GraphqlConstants.ARG_INPUT;
import static io.github.excalibase.schema.GraphqlConstants.ARG_ON_CONFLICT;
import static io.github.excalibase.schema.GraphqlConstants.CREATE_MANY_PREFIX;
import static io.github.excalibase.schema.GraphqlConstants.CREATE_PREFIX;
import static io.github.excalibase.schema.GraphqlConstants.DELETE_PREFIX;
import static io.github.excalibase.schema.GraphqlConstants.UPDATE_PREFIX;

/**
 * MySQL mutation compiler. MySQL has no {@code RETURNING}: rows are read back by primary key through
 * {@link KeyedWrites}, which REST uses too: inserts by the keys sent or generated, updates by the keys they
 * locked before writing, deletes read locked before they go.
 */
public class MysqlMutationCompiler implements MutationCompiler {

    private static final String PRIMARY = "PRIMARY";

    @Override
    public Set<String> arguments(MutationKind kind) {
        return kind == MutationKind.CREATE ? Set.of(ARG_INPUT, ARG_ON_CONFLICT) : MutationCompiler.super.arguments(kind);
    }

    @Override
    public SqlCompiler.CompiledQuery compileMutation(Field field, String fieldName,
                                                     Map<String, Object> params, Map<String, Object> variables,
                                                     MutationBuilder shared) {
        MysqlWrite write = compileWrite(field, fieldName, params, variables, shared);
        return write == null ? null : SqlCompiler.CompiledQuery.twoPhase(write, params);
    }

    private MysqlWrite compileWrite(Field field, String fieldName, Map<String, Object> params,
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

    private MysqlWrite compileSingleInsert(Field field, String tableName,
                                           Map<String, Object> params, Map<String, Object> variables,
                                           MutationBuilder shared) {
        Argument inputArg = shared.findArg(field, ARG_INPUT);
        if (inputArg == null) return null;
        Map<String, Object> inputFields = shared.extractObjectFields(inputArg.getValue(), variables);
        MutationBuilder.OnConflict conflict = shared.onConflict(field, variables);
        if (conflict != null) return compileUpsert(field, tableName, inputFields, conflict, params, shared);
        MutationBuilder.BulkInsertParts row = shared.bulkInsertParts(field, tableName, List.of(inputFields),
                params, false);
        return compileInsert(tableName, row, params, false, shared);
    }

    /**
     * {@code create} with {@code onConflict}: an INSERT when no row holds the constraint's values, else an
     * UPDATE of that row alone with the sent values of {@code update_columns}, the update permission's presets
     * and its filter as the gate, as Postgres's {@code DO UPDATE ... WHERE}. {@code ON DUPLICATE KEY UPDATE}
     * is not used: it would update whichever row any unique key collides with.
     */
    private MysqlWrite compileUpsert(Field field, String tableName, Map<String, Object> inputFields,
                                     MutationBuilder.OnConflict conflict, Map<String, Object> params,
                                     MutationBuilder shared) {
        if (!shared.permitsUpsert(tableName)) {
            throw new IllegalArgumentException("Unknown argument 'onConflict' on field '" + field.getName() + "'");
        }
        shared.requireSettable(tableName, RlsOp.UPDATE, conflict.updateColumns());
        MutationBuilder.BulkInsertParts row = shared.bulkInsertParts(field, tableName, List.of(inputFields),
                params, false);
        if (row == null) return null;
        Map<String, Object> bound = row.boundRows().getFirst();
        List<String> keys = rowKey(tableName, shared);
        List<String> constraint = constraintColumns(tableName, conflict.constraint(), keys, shared);
        String table = shared.qualifiedTable(tableName);
        KeyedWrites.Insert insert = new KeyedWrites.Insert(INSERT_INTO + table + parens(row.columns()) + VALUES
                + row.rowValues().getFirst(), params, bound);
        List<RlsOp> checks = List.of(RlsOp.INSERT, RlsOp.UPDATE);
        KeyedWrites keyed = keyed(tableName, table, row.alias(), row.objectSql(), checks, params, shared);
        MysqlWrite write = new MysqlWrite(tableName, checkReads(tableName, checks), null);
        if (!constraint.stream().allMatch(column -> bound.get(column) != null)) {
            return write.with(keyed.insert(List.of(insert), false));
        }
        KeyedWrites.Condition set = upsertSet(tableName, conflict.updateColumns(), bound, shared);
        KeyedWrites.Condition updatable = updatable(tableName, row.alias(), shared);
        boolean onPrimaryKey = new HashSet<>(constraint).equals(new HashSet<>(keys));
        return write.with(onPrimaryKey ? keyed.upsert(insert, set, updatable)
                : keyed.upsertOn(constraint, insert, set, updatable));
    }

    /** The columns {@code constraint} names: the primary key as {@code PRIMARY}, else a unique index by its name. */
    private static List<String> constraintColumns(String tableName, String constraint, List<String> keys,
                                                  MutationBuilder shared) {
        if (PRIMARY.equalsIgnoreCase(constraint)) {
            if (keys.isEmpty()) {
                throw new IllegalArgumentException(tableName + " has no primary key to conflict on");
            }
            return keys;
        }
        List<String> columns = shared.schemaInfo().getUniqueKey(tableName, constraint);
        if (columns == null) {
            throw new IllegalArgumentException("Unknown constraint '" + constraint + "' on " + tableName
                    + ": name PRIMARY or one of its unique indexes");
        }
        return columns;
    }

    /** The UPDATE's assignments: each update column takes the value sent for it, a preset its own value. */
    private static KeyedWrites.Condition upsertSet(String tableName, List<String> updateColumns,
                                                   Map<String, Object> bound, MutationBuilder shared) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String column : updateColumns) {
            if (!bound.containsKey(column)) {
                throw new IllegalArgumentException("onConflict update_columns '" + column + "' is not in the input");
            }
            values.put(column, bound.get(column));
        }
        shared.presets(tableName, RlsOp.UPDATE).forEach((column, value) ->
                values.put(column, shared.bindValue(tableName, column, value)));
        List<String> assignments = new ArrayList<>();
        Map<String, Object> setParams = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String name = "oc_set" + assignments.size();
            assignments.add(shared.dialect().quoteIdentifier(entry.getKey()) + " = :" + name);
            setParams.put(name, entry.getValue());
        }
        return new KeyedWrites.Condition(String.join(", ", assignments), setParams);
    }

    /** Which existing rows the update may overwrite: the update permission's filter, empty when every row. */
    private static KeyedWrites.Condition updatable(String tableName, String alias, MutationBuilder shared) {
        List<String> conditions = new ArrayList<>();
        Map<String, Object> filterParams = new LinkedHashMap<>();
        shared.filterBuilder().appendRlsConditions(conditions, tableName, alias, filterParams, RlsOp.UPDATE);
        return new KeyedWrites.Condition(String.join(AND, conditions), filterParams);
    }

    private static List<String> rowKey(String tableName, MutationBuilder shared) {
        WriteGuard guard = RlsContext.writeGuard();
        if (guard != null) return guard.rowKey(tableName);
        SchemaInfo schema = shared.schemaInfo();
        return schema.hasPrimaryKey(tableName) ? schema.getPrimaryKeys(tableName) : List.of();
    }

    private MysqlWrite compileInsert(String tableName, MutationBuilder.BulkInsertParts rows,
                                     Map<String, Object> params, boolean many, MutationBuilder shared) {
        if (rows == null) return null;
        String table = shared.qualifiedTable(tableName);
        List<KeyedWrites.Insert> inserts = new ArrayList<>();
        for (int row = 0; row < rows.rowCount(); row++) {
            inserts.add(new KeyedWrites.Insert(INSERT_INTO + table + parens(rows.columns()) + VALUES
                    + rows.rowValues().get(row), params, rows.boundRows().get(row)));
        }
        KeyedWrites keyed = keyed(tableName, table, rows.alias(), rows.objectSql(), RlsOp.INSERT, params, shared);
        // A caller who may not read the table gets the affected_rows object, once, as on Postgres.
        return new MysqlWrite(tableName, checkReads(tableName, RlsOp.INSERT),
                keyed.insert(inserts, many && shared.returnsRows(tableName)));
    }

    private MysqlWrite compileUpdate(Field field, String tableName,
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
        return new MysqlWrite(tableName, checkReads(tableName, RlsOp.UPDATE),
                keyed.update(dml, condition(whereSql), params, set));
    }

    private MysqlWrite compileDelete(Field field, String tableName,
                                     Map<String, Object> params, MutationBuilder shared) {
        String alias = shared.dialect().randAlias();
        String objectSql = shared.queryBuilder().buildObject(field.getSelectionSet(), tableName, alias, params);

        StringBuilder whereSql = new StringBuilder();
        shared.filterBuilder().applyWhere(whereSql, field, alias, params, tableName, RlsOp.DELETE);
        if (whereSql.isEmpty()) return null;

        String table = shared.qualifiedTable(tableName);
        String dml = shared.dialect().cteDelete(alias, table, whereSql.toString(), objectSql);
        KeyedWrites keyed = keyed(tableName, table, alias, objectSql, List.<RlsOp>of(), params, shared);
        return new MysqlWrite(tableName, Set.of(), keyed.delete(dml, condition(whereSql), params));
    }

    /**
     * Rows are read back by primary key as the caller reads them, passing its select filter; none when the
     * caller may not read the table. Every written row must pass the permission's check for {@code operation}.
     */
    private static KeyedWrites keyed(String tableName, String table, String alias, String objectSql, RlsOp operation,
                                     Map<String, Object> params, MutationBuilder shared) {
        return keyed(tableName, table, alias, objectSql, operation == null ? List.<RlsOp>of() : List.of(operation),
                params, shared);
    }

    /** As above, for a write that is more than one operation: an upsert is an insert and an update. */
    private static KeyedWrites keyed(String tableName, String table, String alias, String objectSql,
                                     List<RlsOp> operations, Map<String, Object> params, MutationBuilder shared) {
        KeyedWrites.ReadBack readBack = operations.contains(RlsOp.INSERT) && !shared.returnsRows(tableName)
                ? KeyedWrites.ReadBack.aggregate(objectSql, params) : null;
        if (shared.returnsRows(tableName)) {
            List<String> readable = new ArrayList<>();
            shared.filterBuilder().appendRlsConditions(readable, tableName, alias, params, RlsOp.SELECT);
            readBack = new KeyedWrites.ReadBack(objectSql, String.join(AND, readable), params);
        }
        WriteGuard guard = RlsContext.writeGuard();
        List<String> passes = new ArrayList<>();
        Map<String, Object> checkParams = new LinkedHashMap<>();
        for (RlsOp operation : guard == null ? List.<RlsOp>of() : operations) {
            RlsWhereContributor.Contribution contribution = guard.check(tableName, alias, operation);
            if (contribution != null) {
                passes.add(parens(contribution.sql()));
                checkParams.putAll(contribution.params());
            }
        }
        KeyedWrites.Condition check = passes.isEmpty() ? null
                : new KeyedWrites.Condition(String.join(AND, passes), checkParams);
        return new KeyedWrites(shared.dialect(), target(tableName, table, alias, guard, shared), readBack, check);
    }

    /** The database's primary key, whether or not the caller may read it, and whether MySQL generates it. */
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
        return checkReads(tableName, List.of(operation));
    }

    private static Set<String> checkReads(String tableName, List<RlsOp> operations) {
        WriteGuard guard = RlsContext.writeGuard();
        Set<String> reads = new HashSet<>();
        for (RlsOp operation : guard == null ? List.<RlsOp>of() : operations) {
            reads.addAll(guard.checkReads(tableName, operation));
        }
        return reads;
    }

    /** The filter of a {@code WHERE} clause the filter builder wrote, without the keyword. */
    private static String condition(StringBuilder whereSql) {
        String where = whereSql.toString();
        return where.startsWith(WHERE) ? where.substring(WHERE.length()) : where;
    }
}
