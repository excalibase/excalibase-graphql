package io.github.excalibase.mysql;

import graphql.language.*;
import io.github.excalibase.compiler.MutationBuilder;
import io.github.excalibase.spi.MutationCompiler;
import io.github.excalibase.compiler.SqlCompiler;

import java.util.*;
import java.util.stream.Collectors;
import static io.github.excalibase.schema.GraphqlConstants.*;
import static io.github.excalibase.compiler.SqlKeywords.*;

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
        if (fieldName.startsWith(CREATE_MANY_PREFIX)) {
            String tableName = shared.resolveMutationTable(fieldName.substring(CREATE_MANY_PREFIX.length()));
            if (tableName != null) return compileBulkInsert(field, fieldName, tableName, params, variables, shared);
        } else if (fieldName.startsWith(CREATE_PREFIX)) {
            String tableName = shared.resolveMutationTable(fieldName.substring(CREATE_PREFIX.length()));
            if (tableName != null) return compileInsert(field, fieldName, tableName, params, variables, shared);
        } else if (fieldName.startsWith(UPDATE_PREFIX)) {
            String tableName = shared.resolveMutationTable(fieldName.substring(UPDATE_PREFIX.length()));
            if (tableName != null) return compileUpdate(field, fieldName, tableName, params, variables, shared);
        } else if (fieldName.startsWith(DELETE_PREFIX)) {
            String tableName = shared.resolveMutationTable(fieldName.substring(DELETE_PREFIX.length()));
            if (tableName != null) return compileDelete(field, fieldName, tableName, params, variables, shared);
        }
        return null;
    }

    private MutationBuilder.MysqlMutationResult compileInsert(Field field, String fieldName, String tableName,
                                                               Map<String, Object> params, Map<String, Object> variables,
                                                               MutationBuilder shared) {
        Argument inputArg = shared.findArg(field, ARG_INPUT);
        if (inputArg == null) return null;

        Map<String, Object> inputFields = shared.extractObjectFields(inputArg.getValue(), variables);
        String alias = shared.dialect().randAlias();
        String objectSql = shared.queryBuilder().buildObject(field.getSelectionSet(), tableName, alias);

        List<String> cols = new ArrayList<>();
        List<String> vals = new ArrayList<>();
        for (var entry : inputFields.entrySet()) {
            cols.add(shared.dialect().quoteIdentifier(entry.getKey()));
            String paramName = namedParam(P_INSERT, entry.getKey(), params.size());
            vals.add(param(paramName));
            params.put(paramName, entry.getValue());
        }

        String dmlSql = shared.dialect().cteInsert(alias, shared.qualifiedTable(tableName),
                joinCols(cols), joinCols(vals), "", objectSql);

        String pk = shared.schemaInfo().getPrimaryKey(tableName);
        String lastIdParam = namedParam(P_LAST_ID, params.size());
        String innerSelect = SELECT + objectSql + FROM + shared.qualifiedTable(tableName) + " " + alias
                + WHERE + alias + "." + shared.dialect().quoteIdentifier(pk) + ASSIGN + param(lastIdParam);
        String selectSql = SELECT + shared.dialect().buildObject(List.of(
                "'" + fieldName + "', " + parens(innerSelect)));

        return new MutationBuilder.MysqlMutationResult(dmlSql, selectSql, lastIdParam);
    }

    private MutationBuilder.MysqlMutationResult compileBulkInsert(Field field, String fieldName, String tableName,
                                                                    Map<String, Object> params, Map<String, Object> variables,
                                                                    MutationBuilder shared) {
        Argument inputsArg = shared.findArg(field, ARG_INPUTS);
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
                String paramName = namedParam(P_BULK_INSERT, col + "_" + i, params.size());
                vals.add(param(paramName));
                params.put(paramName, row.get(col));
            }
            valueRows.add(parens(joinCols(vals)));
        }

        String dmlSql = shared.dialect().cteBulkInsert(alias, shared.qualifiedTable(tableName),
                joinCols(colsSql), joinCols(valueRows), objectSql);

        String pk = shared.schemaInfo().getPrimaryKey(tableName);
        String lastIdParam = namedParam(P_LAST_ID, params.size());
        int rowCount = rows.size();
        String pkCol = alias + "." + shared.dialect().quoteIdentifier(pk);
        String innerSelect = SELECT + shared.dialect().coalesceArray(shared.dialect().aggregateArray(objectSql))
                + FROM + shared.qualifiedTable(tableName) + " " + alias
                + WHERE + pkCol + " >= " + param(lastIdParam)
                + AND + pkCol + " < " + param(lastIdParam) + " + " + rowCount;
        String selectSql = SELECT + shared.dialect().buildObject(List.of(
                "'" + fieldName + "', " + parens(innerSelect)));

        return new MutationBuilder.MysqlMutationResult(dmlSql, selectSql, lastIdParam);
    }

    private MutationBuilder.MysqlMutationResult compileUpdate(Field field, String fieldName, String tableName,
                                                               Map<String, Object> params, Map<String, Object> variables,
                                                               MutationBuilder shared) {
        Argument inputArg = shared.findArg(field, ARG_INPUT);
        if (inputArg == null) return null;

        Map<String, Object> inputFields = shared.extractObjectFields(inputArg.getValue(), variables);
        String alias = shared.dialect().randAlias();
        String objectSql = shared.queryBuilder().buildObject(field.getSelectionSet(), tableName, alias);

        List<String> setClauses = new ArrayList<>();
        for (var entry : inputFields.entrySet()) {
            String paramName = namedParam(P_UPDATE, entry.getKey(), params.size());
            setClauses.add(buildAssign(shared.dialect().quoteIdentifier(entry.getKey()), paramName));
            params.put(paramName, entry.getValue());
        }
        if (setClauses.isEmpty()) return null;

        // Build WHERE from where argument (filter-based)
        StringBuilder whereSql = new StringBuilder();
        shared.filterBuilder().applyWhere(whereSql, field, alias, params, tableName);
        if (whereSql.isEmpty()) return null; // Require where to prevent accidental full-table update

        // MySQL two-phase: DML first, then SELECT affected rows
        String dmlSql = shared.dialect().cteUpdate(alias, shared.qualifiedTable(tableName),
                joinCols(setClauses), whereSql.toString(), objectSql);

        String innerSelect = SELECT + shared.dialect().coalesceArray(shared.dialect().aggregateArray(objectSql))
                + FROM + shared.qualifiedTable(tableName) + " " + alias + whereSql;
        String selectSql = SELECT + shared.dialect().buildObject(List.of(
                "'" + fieldName + "', " + parens(innerSelect)));

        return new MutationBuilder.MysqlMutationResult(dmlSql, selectSql, null);
    }

    private MutationBuilder.MysqlMutationResult compileDelete(Field field, String fieldName, String tableName,
                                                               Map<String, Object> params, Map<String, Object> variables,
                                                               MutationBuilder shared) {
        String alias = shared.dialect().randAlias();
        String objectSql = shared.queryBuilder().buildObject(field.getSelectionSet(), tableName, alias);

        StringBuilder whereSql = new StringBuilder();
        shared.filterBuilder().applyWhere(whereSql, field, alias, params, tableName);
        if (whereSql.isEmpty()) return null;

        String innerSelect = SELECT + shared.dialect().coalesceArray(shared.dialect().aggregateArray(objectSql))
                + FROM + shared.qualifiedTable(tableName) + " " + alias + whereSql;
        String selectSql = SELECT + shared.dialect().buildObject(List.of(
                "'" + fieldName + "', " + parens(innerSelect)));

        String dmlSql = shared.dialect().cteDelete(alias, shared.qualifiedTable(tableName),
                whereSql.toString(), objectSql);

        return new MutationBuilder.MysqlMutationResult(dmlSql, selectSql, MutationBuilder.MUTATION_DELETE);
    }
}
