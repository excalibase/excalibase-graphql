package io.github.excalibase.mysql;

import graphql.language.Argument;
import graphql.language.Field;
import io.github.excalibase.compiler.KeyedWrites;
import io.github.excalibase.compiler.MutationBuilder;
import io.github.excalibase.compiler.SqlCompiler;
import io.github.excalibase.security.RlsOp;
import io.github.excalibase.spi.MutationCompiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * MySQL mutation compiler. MySQL has no {@code RETURNING}: rows are read back by primary key through
 * {@link KeyedWrites}, which REST uses too: inserts by the keys sent or generated, updates by the keys they
 * locked before writing, deletes read locked before they go.
 */
public class MysqlMutationCompiler implements MutationCompiler {

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
                return compileInsert(fieldName, tableName,
                        shared.bulkInsertParts(field, tableName, params, variables, false), params, true, shared);
            }
        } else if (fieldName.startsWith(CREATE_PREFIX)) {
            String tableName = shared.resolveMutationTable(fieldName.substring(CREATE_PREFIX.length()), fieldName);
            if (tableName != null) {
                return compileSingleInsert(field, fieldName, tableName, params, variables, shared);
            }
        } else if (fieldName.startsWith(UPDATE_PREFIX)) {
            String tableName = shared.resolveMutationTable(fieldName.substring(UPDATE_PREFIX.length()), fieldName);
            if (tableName != null) return compileUpdate(field, fieldName, tableName, params, variables, shared);
        } else if (fieldName.startsWith(DELETE_PREFIX)) {
            String tableName = shared.resolveMutationTable(fieldName.substring(DELETE_PREFIX.length()), fieldName);
            if (tableName != null) return compileDelete(field, fieldName, tableName, params, shared);
        }
        return null;
    }

    private MysqlWrite compileSingleInsert(Field field, String fieldName, String tableName,
                                           Map<String, Object> params, Map<String, Object> variables,
                                           MutationBuilder shared) {
        Argument inputArg = shared.findArg(field, ARG_INPUT);
        if (inputArg == null) return null;
        Map<String, Object> inputFields = shared.extractObjectFields(inputArg.getValue(), variables);
        MutationBuilder.BulkInsertParts row = shared.bulkInsertParts(field, tableName, List.of(inputFields),
                params, false);
        return compileInsert(fieldName, tableName, row, params, false, shared);
    }

    private MysqlWrite compileInsert(String fieldName, String tableName, MutationBuilder.BulkInsertParts rows,
                                     Map<String, Object> params, boolean many, MutationBuilder shared) {
        if (rows == null) return null;
        String table = shared.qualifiedTable(tableName);
        List<KeyedWrites.Insert> inserts = new ArrayList<>();
        for (int row = 0; row < rows.rowCount(); row++) {
            inserts.add(new KeyedWrites.Insert(INSERT_INTO + table + parens(rows.columns()) + VALUES
                    + rows.rowValues().get(row), params, rows.boundRows().get(row)));
        }
        KeyedWrites keyed = keyed(tableName, table, rows.alias(), rows.objectSql(), params, shared);
        return new MysqlWrite(fieldName, keyed.insert(inserts, many));
    }

    private MysqlWrite compileUpdate(Field field, String fieldName, String tableName,
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
        KeyedWrites keyed = keyed(tableName, table, update.alias(), update.objectSql(), params, shared);
        return new MysqlWrite(fieldName, keyed.update(dml, condition(whereSql), params, set));
    }

    private MysqlWrite compileDelete(Field field, String fieldName, String tableName,
                                     Map<String, Object> params, MutationBuilder shared) {
        String alias = shared.dialect().randAlias();
        String objectSql = shared.queryBuilder().buildObject(field.getSelectionSet(), tableName, alias, params);

        StringBuilder whereSql = new StringBuilder();
        shared.filterBuilder().applyWhere(whereSql, field, alias, params, tableName, RlsOp.DELETE);
        if (whereSql.isEmpty()) return null;

        String table = shared.qualifiedTable(tableName);
        String dml = shared.dialect().cteDelete(alias, table, whereSql.toString(), objectSql);
        KeyedWrites keyed = keyed(tableName, table, alias, objectSql, params, shared);
        return new MysqlWrite(fieldName, keyed.delete(dml, condition(whereSql), params));
    }

    /** Rows are read back by primary key as the caller reads them; none when the caller may not read the table. */
    private static KeyedWrites keyed(String tableName, String table, String alias, String objectSql,
                                     Map<String, Object> params, MutationBuilder shared) {
        KeyedWrites.ReadBack readBack = shared.returnsRows(tableName)
                ? new KeyedWrites.ReadBack(objectSql, "", params) : null;
        List<String> keys = shared.schemaInfo().hasPrimaryKey(tableName)
                ? shared.schemaInfo().getPrimaryKeys(tableName) : List.of();
        return new KeyedWrites(shared.dialect(), tableName, table, alias, keys, readBack);
    }

    /** The filter of a {@code WHERE} clause the filter builder wrote, without the keyword. */
    private static String condition(StringBuilder whereSql) {
        String where = whereSql.toString();
        return where.startsWith(WHERE) ? where.substring(WHERE.length()) : where;
    }
}
