package io.github.excalibase.mysql;

import io.github.excalibase.*;
import graphql.language.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MySQL mutation compiler. Uses two-phase DML + SELECT (no RETURNING).
 */
public class MysqlMutationCompiler implements MutationCompiler {

    public MysqlMutationCompiler() {}

    @Override
    public SqlCompiler.CompiledQuery compileMutation(Field field, String fieldName,
            Map<String, Object> params, Map<String, Object> variables,
            MutationBuilder shared) {
        MutationBuilder.MysqlMutationResult result = compileMysqlMutation(field, fieldName, params, variables, shared);
        if (result == null) return null;
        return new SqlCompiler.CompiledQuery(result.selectSql(), params, result.dmlSql(), result.lastInsertIdParam());
    }

    private MutationBuilder.MysqlMutationResult compileMysqlMutation(Field field, String fieldName,
                                                                      Map<String, Object> params, Map<String, Object> variables,
                                                                      MutationBuilder shared) {
        if (fieldName.startsWith("createMany")) {
            String tableName = shared.resolveMutationTable(fieldName.substring("createMany".length()));
            if (tableName != null) return compileBulkInsert(field, fieldName, tableName, params, variables, shared);
        } else if (fieldName.startsWith("create")) {
            String tableName = shared.resolveMutationTable(fieldName.substring("create".length()));
            if (tableName != null) return compileInsert(field, fieldName, tableName, params, variables, shared);
        } else if (fieldName.startsWith("update")) {
            String tableName = shared.resolveMutationTable(fieldName.substring("update".length()));
            if (tableName != null) return compileUpdate(field, fieldName, tableName, params, variables, shared);
        } else if (fieldName.startsWith("delete")) {
            String tableName = shared.resolveMutationTable(fieldName.substring("delete".length()));
            if (tableName != null) return compileDelete(field, fieldName, tableName, params, variables, shared);
        }
        return null;
    }

    private MutationBuilder.MysqlMutationResult compileInsert(Field field, String fieldName, String tableName,
                                                               Map<String, Object> params, Map<String, Object> variables,
                                                               MutationBuilder shared) {
        Argument inputArg = shared.findArg(field, "input");
        if (inputArg == null) return null;

        Map<String, Object> inputFields = shared.extractObjectFields(inputArg.getValue(), variables);
        String alias = shared.dialect().randAlias();
        String objectSql = shared.queryBuilder().buildObject(field.getSelectionSet(), tableName, alias);

        List<String> cols = new ArrayList<>();
        List<String> vals = new ArrayList<>();
        for (var entry : inputFields.entrySet()) {
            cols.add(shared.dialect().quoteIdentifier(entry.getKey()));
            String paramName = "ins_" + entry.getKey() + "_" + params.size();
            vals.add(":" + paramName);
            params.put(paramName, entry.getValue());
        }

        String dmlSql = "INSERT INTO " + shared.qualifiedTable(tableName)
                + " (" + String.join(", ", cols) + ") VALUES (" + String.join(", ", vals) + ")";

        String pk = shared.schemaInfo().getPrimaryKey(tableName);
        String lastIdParam = "last_id_" + params.size();
        String selectSql = "SELECT " + shared.dialect().buildObject(List.of(
                "'" + fieldName + "', (" +
                "SELECT " + objectSql + " FROM " + shared.qualifiedTable(tableName) + " " + alias
                + " WHERE " + alias + "." + shared.dialect().quoteIdentifier(pk) + " = :" + lastIdParam + ")"));

        return new MutationBuilder.MysqlMutationResult(dmlSql, selectSql, lastIdParam);
    }

    private MutationBuilder.MysqlMutationResult compileBulkInsert(Field field, String fieldName, String tableName,
                                                                    Map<String, Object> params, Map<String, Object> variables,
                                                                    MutationBuilder shared) {
        Argument inputsArg = shared.findArg(field, "inputs");
        if (inputsArg == null) return null;

        List<Map<String, Object>> rows = shared.extractArrayOfObjects(inputsArg.getValue(), variables);
        if (rows.isEmpty()) return null;

        String alias = shared.dialect().randAlias();
        String objectSql = shared.queryBuilder().buildObject(field.getSelectionSet(), tableName, alias);

        List<String> colNames = new ArrayList<>(rows.getFirst().keySet());
        List<String> colsSql = colNames.stream().map(shared.dialect()::quoteIdentifier).collect(Collectors.toList());

        List<String> valueRows = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            List<String> vals = new ArrayList<>();
            for (String col : colNames) {
                String paramName = "bins_" + col + "_" + i + "_" + params.size();
                vals.add(":" + paramName);
                params.put(paramName, row.get(col));
            }
            valueRows.add("(" + String.join(", ", vals) + ")");
        }

        String dmlSql = "INSERT INTO " + shared.qualifiedTable(tableName)
                + " (" + String.join(", ", colsSql) + ") VALUES " + String.join(", ", valueRows);

        String pk = shared.schemaInfo().getPrimaryKey(tableName);
        String lastIdParam = "last_id_" + params.size();
        int rowCount = rows.size();
        String selectSql = "SELECT " + shared.dialect().buildObject(List.of(
                "'" + fieldName + "', (" +
                "SELECT " + shared.dialect().coalesceArray(shared.dialect().aggregateArray(objectSql))
                + " FROM " + shared.qualifiedTable(tableName) + " " + alias
                + " WHERE " + alias + "." + shared.dialect().quoteIdentifier(pk) + " >= :" + lastIdParam
                + " AND " + alias + "." + shared.dialect().quoteIdentifier(pk) + " < :" + lastIdParam + " + " + rowCount
                + ")"));

        return new MutationBuilder.MysqlMutationResult(dmlSql, selectSql, lastIdParam);
    }

    private MutationBuilder.MysqlMutationResult compileUpdate(Field field, String fieldName, String tableName,
                                                               Map<String, Object> params, Map<String, Object> variables,
                                                               MutationBuilder shared) {
        Argument inputArg = shared.findArg(field, "input");
        if (inputArg == null) return null;

        Map<String, Object> inputFields = shared.extractObjectFields(inputArg.getValue(), variables);
        List<String> pks = shared.schemaInfo().getPrimaryKeys(tableName);

        Argument idArg = shared.findArg(field, "id");
        if (idArg != null && pks.size() == 1) {
            inputFields.putIfAbsent(pks.get(0), shared.filterBuilder().extractValue(idArg.getValue(), variables));
        }
        String alias = shared.dialect().randAlias();
        String objectSql = shared.queryBuilder().buildObject(field.getSelectionSet(), tableName, alias);

        List<String> setClauses = new ArrayList<>();
        List<String> whereClauses = new ArrayList<>();
        List<String> selectWhereClauses = new ArrayList<>();

        for (var entry : inputFields.entrySet()) {
            String paramName = "upd_" + entry.getKey() + "_" + params.size();
            params.put(paramName, entry.getValue());
            if (pks.contains(entry.getKey())) {
                whereClauses.add(shared.dialect().quoteIdentifier(entry.getKey()) + " = :" + paramName);
                selectWhereClauses.add(alias + "." + shared.dialect().quoteIdentifier(entry.getKey()) + " = :" + paramName);
            } else {
                setClauses.add(shared.dialect().quoteIdentifier(entry.getKey()) + " = :" + paramName);
            }
        }

        if (setClauses.isEmpty() || whereClauses.isEmpty()) return null;

        String dmlSql = "UPDATE " + shared.qualifiedTable(tableName)
                + " SET " + String.join(", ", setClauses)
                + " WHERE " + String.join(" AND ", whereClauses);

        String selectSql = "SELECT " + shared.dialect().buildObject(List.of(
                "'" + fieldName + "', (" +
                "SELECT " + objectSql + " FROM " + shared.qualifiedTable(tableName) + " " + alias
                + " WHERE " + String.join(" AND ", selectWhereClauses) + ")"));

        return new MutationBuilder.MysqlMutationResult(dmlSql, selectSql, null);
    }

    private MutationBuilder.MysqlMutationResult compileDelete(Field field, String fieldName, String tableName,
                                                               Map<String, Object> params, Map<String, Object> variables,
                                                               MutationBuilder shared) {
        Argument inputArg = shared.findArg(field, "input");
        Map<String, Object> inputFields;
        if (inputArg != null) {
            inputFields = shared.extractObjectFields(inputArg.getValue(), variables);
        } else {
            inputFields = new LinkedHashMap<>();
        }
        List<String> pks = shared.schemaInfo().getPrimaryKeys(tableName);

        Argument idArg = shared.findArg(field, "id");
        if (idArg != null && pks.size() == 1) {
            inputFields.putIfAbsent(pks.get(0), shared.filterBuilder().extractValue(idArg.getValue(), variables));
        }
        if (inputFields.isEmpty()) return null;
        String alias = shared.dialect().randAlias();
        String objectSql = shared.queryBuilder().buildObject(field.getSelectionSet(), tableName, alias);

        List<String> whereClauses = new ArrayList<>();
        List<String> selectWhereClauses = new ArrayList<>();
        for (String pk : pks) {
            Object val = inputFields.get(pk);
            if (val == null) return null;
            String paramName = "del_" + pk + "_" + params.size();
            whereClauses.add(shared.dialect().quoteIdentifier(pk) + " = :" + paramName);
            selectWhereClauses.add(alias + "." + shared.dialect().quoteIdentifier(pk) + " = :" + paramName);
            params.put(paramName, val);
        }

        String selectSql = "SELECT " + shared.dialect().buildObject(List.of(
                "'" + fieldName + "', (" +
                "SELECT " + objectSql + " FROM " + shared.qualifiedTable(tableName) + " " + alias
                + " WHERE " + String.join(" AND ", selectWhereClauses) + ")"));

        String dmlSql = "DELETE FROM " + shared.qualifiedTable(tableName)
                + " WHERE " + String.join(" AND ", whereClauses);

        return new MutationBuilder.MysqlMutationResult(dmlSql, selectSql, MutationBuilder.MUTATION_DELETE);
    }
}
