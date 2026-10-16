package io.github.excalibase.mysql;

import graphql.language.Argument;
import graphql.language.Field;
import io.github.excalibase.compiler.MutationBuilder;
import io.github.excalibase.compiler.SqlCompilationException;
import io.github.excalibase.compiler.SqlCompiler;
import io.github.excalibase.security.RlsOp;
import io.github.excalibase.spi.MutationCompiler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.github.excalibase.compiler.SqlKeywords.FROM;
import static io.github.excalibase.compiler.SqlKeywords.INSERT_INTO;
import static io.github.excalibase.compiler.SqlKeywords.P_UPDATE;
import static io.github.excalibase.compiler.SqlKeywords.SELECT;
import static io.github.excalibase.compiler.SqlKeywords.SET;
import static io.github.excalibase.compiler.SqlKeywords.VALUES;
import static io.github.excalibase.compiler.SqlKeywords.WHERE;
import static io.github.excalibase.compiler.SqlKeywords.joinCols;
import static io.github.excalibase.compiler.SqlKeywords.parens;
import static io.github.excalibase.compiler.SqlKeywords.UPDATE;
import static io.github.excalibase.schema.GraphqlConstants.ARG_INPUT;
import static io.github.excalibase.schema.GraphqlConstants.CREATE_MANY_PREFIX;
import static io.github.excalibase.schema.GraphqlConstants.CREATE_PREFIX;
import static io.github.excalibase.schema.GraphqlConstants.DELETE_PREFIX;
import static io.github.excalibase.schema.GraphqlConstants.UPDATE_PREFIX;

/**
 * MySQL mutation compiler. MySQL has no {@code RETURNING}: inserts and updates read their rows back by
 * primary key, inserts by the keys sent or generated, updates by the keys they locked before writing.
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
                        shared.bulkInsertParts(field, tableName, params, variables, false), true, shared);
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
        return compileInsert(fieldName, tableName, row, false, shared);
    }

    private MysqlWrite compileInsert(String fieldName, String tableName, MutationBuilder.BulkInsertParts rows,
                                     boolean many, MutationBuilder shared) {
        if (rows == null) return null;
        List<String> keys = requireKeys(tableName, shared);
        String table = shared.qualifiedTable(tableName);
        List<String> statements = new ArrayList<>();
        List<List<Object>> sentKeys = new ArrayList<>();
        for (int row = 0; row < rows.rowCount(); row++) {
            statements.add(INSERT_INTO + table + parens(rows.columns()) + VALUES + rows.rowValues().get(row));
            Map<String, Object> bound = rows.boundRows().get(row);
            List<Object> sent = new ArrayList<>();
            keys.forEach(key -> sent.add(bound.get(key)));
            sentKeys.add(Collections.unmodifiableList(sent));
        }
        MysqlWrite.KeyedRead readBack = readBack(fieldName, rows.objectSql(), many, table, rows.alias(), keys, shared);
        return new MysqlWrite.Insert(tableName, keys, List.copyOf(statements), Collections.unmodifiableList(sentKeys),
                readBack);
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

        List<String> keys = requireKeys(tableName, shared);
        String table = shared.qualifiedTable(tableName);
        MysqlWrite.KeyedRead readBack = readBack(fieldName, update.objectSql(), true, table, update.alias(), keys,
                shared);
        String lockSql = SELECT + joinCols(readBack.keyRefs()) + FROM + table + " " + update.alias() + whereSql
                + " FOR UPDATE";
        String updateHead = UPDATE + table + " " + update.alias() + SET + joinCols(update.setClauses()) + WHERE;

        Map<String, Object> written = MutationBuilder.withPresets(inputFields,
                shared.presets(tableName, RlsOp.UPDATE));
        Map<Integer, Object> keyAssignments = new HashMap<>();
        for (int column = 0; column < keys.size(); column++) {
            String key = keys.get(column);
            if (written.containsKey(key)) {
                keyAssignments.put(column, shared.bindValue(tableName, key, written.get(key)));
            }
        }
        return new MysqlWrite.Update(lockSql, updateHead, Collections.unmodifiableMap(keyAssignments), readBack);
    }

    private MysqlWrite compileDelete(Field field, String fieldName, String tableName,
                                     Map<String, Object> params, MutationBuilder shared) {
        String alias = shared.dialect().randAlias();
        String objectSql = shared.queryBuilder().buildObject(field.getSelectionSet(), tableName, alias, params);

        StringBuilder whereSql = new StringBuilder();
        shared.filterBuilder().applyWhere(whereSql, field, alias, params, tableName, RlsOp.DELETE);
        if (whereSql.isEmpty()) return null;

        String table = shared.qualifiedTable(tableName);
        String innerSelect = SELECT + shared.dialect().coalesceArray(shared.dialect().aggregateArray(objectSql))
                + FROM + table + " " + alias + whereSql;
        String selectSql = SELECT + shared.dialect().buildObject(List.of(
                "'" + fieldName + "', " + parens(innerSelect)));
        String deleteSql = shared.dialect().cteDelete(alias, table, whereSql.toString(), objectSql);
        return new MysqlWrite.Delete(selectSql, deleteSql);
    }

    /** {@code SELECT JSON_OBJECT('field', (SELECT <rows> FROM table alias WHERE <keys>))}, split around the keys. */
    private static MysqlWrite.KeyedRead readBack(String fieldName, String objectSql, boolean many, String table,
                                                 String alias, List<String> keys, MutationBuilder shared) {
        String rows = many ? shared.dialect().coalesceArray(shared.dialect().aggregateArray(objectSql)) : objectSql;
        String head = SELECT + "JSON_OBJECT('" + fieldName + "', (" + SELECT + rows + FROM + table + " " + alias + WHERE;
        List<String> keyRefs = keys.stream().map(key -> alias + "." + shared.dialect().quoteIdentifier(key)).toList();
        return new MysqlWrite.KeyedRead(head, "))", keyRefs);
    }

    /** A written row is found again by its primary key; a table without one cannot be read back. */
    private static List<String> requireKeys(String tableName, MutationBuilder shared) {
        if (!shared.schemaInfo().hasPrimaryKey(tableName)) {
            throw new SqlCompilationException("Table " + tableName
                    + " has no primary key: MySQL writes are read back by key, so it cannot be written here");
        }
        return List.copyOf(shared.schemaInfo().getPrimaryKeys(tableName));
    }
}
