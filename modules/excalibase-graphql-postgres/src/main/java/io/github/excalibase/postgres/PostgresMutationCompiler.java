package io.github.excalibase.postgres;

import graphql.language.*;
import io.github.excalibase.compiler.MutationBuilder;
import io.github.excalibase.spi.MutationCompiler;
import io.github.excalibase.compiler.SqlCompiler;

import java.util.*;

/**
 * PostgreSQL mutation compiler. Uses CTE + RETURNING pattern.
 */
public class PostgresMutationCompiler implements MutationCompiler {

    public PostgresMutationCompiler() {}


    @Override
    public SqlCompiler.CompiledQuery compileMutation(Field field, String fieldName,
                                                     Map<String, Object> params, Map<String, Object> variables,
                                                     MutationBuilder shared) {
        String sql = null;
        if (fieldName.startsWith("update") && fieldName.endsWith("Collection")) {
            String typePart = fieldName.substring("update".length(), fieldName.length() - "Collection".length());
            String tableName = shared.resolveMutationTable(typePart);
            if (tableName != null) sql = compileUpdateCollection(field, tableName, params, variables, shared);
        } else if (fieldName.startsWith("deleteFrom") && fieldName.endsWith("Collection")) {
            String typePart = fieldName.substring("deleteFrom".length(), fieldName.length() - "Collection".length());
            String tableName = shared.resolveMutationTable(typePart);
            if (tableName != null) sql = compileDeleteFromCollection(field, tableName, params, variables, shared);
        } else if (fieldName.startsWith("createMany")) {
            String tableName = shared.resolveMutationTable(fieldName.substring("createMany".length()));
            if (tableName != null) sql = compileBulkInsert(field, tableName, params, variables, shared);
        } else if (fieldName.startsWith("create")) {
            String tableName = shared.resolveMutationTable(fieldName.substring("create".length()));
            if (tableName != null) sql = compileInsert(field, tableName, params, variables, shared);
        } else if (fieldName.startsWith("update")) {
            String tableName = shared.resolveMutationTable(fieldName.substring("update".length()));
            if (tableName != null) sql = compileUpdate(field, tableName, params, variables, shared);
        } else if (fieldName.startsWith("delete")) {
            String tableName = shared.resolveMutationTable(fieldName.substring("delete".length()));
            if (tableName != null) sql = compileDelete(field, tableName, params, variables, shared);
        }
        if (sql == null) return null;
        String wrappedSql = wrapMutationResult(sql, fieldName);
        return new SqlCompiler.CompiledQuery(wrappedSql, params);
    }

    String compileInsert(Field field, String tableName, Map<String, Object> params,
                         Map<String, Object> variables, MutationBuilder shared) {
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
            String enumCast = shared.getEnumCastForMutation(tableName, entry.getKey());
            Object value = shared.convertCompositeValue(tableName, entry.getKey(), entry.getValue());
            vals.add(":" + paramName + enumCast);
            params.put(paramName, value);
        }

        // Check for onConflict (upsert)
        Argument onConflictArg = shared.findArg(field, "onConflict");
        String onConflictSql = "";
        if (onConflictArg != null && onConflictArg.getValue() instanceof ObjectValue ocOv) {
            String constraint = null;
            List<String> updateCols = new ArrayList<>();
            for (ObjectField of : ocOv.getObjectFields()) {
                if ("constraint".equals(of.getName())) {
                    constraint = shared.filterBuilder().extractValue(of.getValue()).toString();
                } else if ("update_columns".equals(of.getName()) && of.getValue() instanceof ArrayValue av) {
                    for (Value<?> v : av.getValues()) {
                        updateCols.add(shared.filterBuilder().extractValue(v).toString());
                    }
                }
            }
            if (constraint != null && !updateCols.isEmpty()) {
                onConflictSql = " " + shared.dialect().onConflict(List.of(constraint), updateCols);
            }
        }

        return "WITH " + alias + " AS (INSERT INTO " + shared.qualifiedTable(tableName)
                + " (" + String.join(", ", cols) + ") VALUES (" + String.join(", ", vals) + ")"
                + onConflictSql
                + " RETURNING *) SELECT " + objectSql + " FROM " + alias;
    }

    String compileBulkInsert(Field field, String tableName, Map<String, Object> params,
                             Map<String, Object> variables, MutationBuilder shared) {
        Argument inputsArg = shared.findArg(field, "inputs");
        if (inputsArg == null) return null;

        List<Map<String, Object>> rows = shared.extractArrayOfObjects(inputsArg.getValue(), variables);
        if (rows.isEmpty()) return null;

        String alias = shared.dialect().randAlias();
        String objectSql = shared.queryBuilder().buildObject(field.getSelectionSet(), tableName, alias);

        List<String> colNames = new ArrayList<>(rows.getFirst().keySet());
        List<String> colsSql = colNames.stream().map(shared.dialect()::quoteIdentifier).toList();

        List<String> valueRows = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            List<String> vals = new ArrayList<>();
            for (String col : colNames) {
                String paramName = "bins_" + col + "_" + i + "_" + params.size();
                String enumCast = shared.getEnumCastForMutation(tableName, col);
                vals.add(":" + paramName + enumCast);
                params.put(paramName, row.get(col));
            }
            valueRows.add("(" + String.join(", ", vals) + ")");
        }

        return "WITH " + alias + " AS (INSERT INTO " + shared.qualifiedTable(tableName)
                + " (" + String.join(", ", colsSql) + ") VALUES " + String.join(", ", valueRows)
                + " RETURNING *) SELECT " + shared.dialect().coalesceArray(shared.dialect().aggregateArray(objectSql)) + " FROM " + alias;
    }

    String compileUpdate(Field field, String tableName, Map<String, Object> params,
                         Map<String, Object> variables, MutationBuilder shared) {
        Argument inputArg = shared.findArg(field, "input");
        if (inputArg == null) return null;

        Map<String, Object> inputFields = shared.extractObjectFields(inputArg.getValue(), variables);
        List<String> pks = shared.schemaInfo().getPrimaryKeys(tableName);
        String alias = shared.dialect().randAlias();
        String objectSql = shared.queryBuilder().buildObject(field.getSelectionSet(), tableName, alias);

        // Support (id: X, input: {col: Y}) style
        Argument idArg = shared.findArg(field, "id");
        if (idArg != null && pks.size() == 1) {
            inputFields.putIfAbsent(pks.get(0), shared.filterBuilder().extractValue(idArg.getValue(), variables));
        }

        List<String> setClauses = new ArrayList<>();
        List<String> whereClauses = new ArrayList<>();

        for (var entry : inputFields.entrySet()) {
            String paramName = "upd_" + entry.getKey() + "_" + params.size();
            String enumCast = shared.getEnumCastForMutation(tableName, entry.getKey());
            params.put(paramName, entry.getValue());
            if (pks.contains(entry.getKey())) {
                whereClauses.add(shared.dialect().quoteIdentifier(entry.getKey()) + " = :" + paramName);
            } else {
                setClauses.add(shared.dialect().quoteIdentifier(entry.getKey()) + " = :" + paramName + enumCast);
            }
        }

        if (setClauses.isEmpty() || whereClauses.isEmpty()) return null;

        return "WITH " + alias + " AS (UPDATE " + shared.qualifiedTable(tableName)
                + " SET " + String.join(", ", setClauses)
                + " WHERE " + String.join(" AND ", whereClauses)
                + " RETURNING *) SELECT " + objectSql + " FROM " + alias;
    }

    String compileDelete(Field field, String tableName, Map<String, Object> params,
                         Map<String, Object> variables, MutationBuilder shared) {
        Argument inputArg = shared.findArg(field, "input");
        if (inputArg == null) {
            Argument idArg = shared.findArg(field, "id");
            if (idArg == null) return null;
            List<String> pks = shared.schemaInfo().getPrimaryKeys(tableName);
            if (pks.size() != 1) return null;
            String alias = shared.dialect().randAlias();
            String objectSql = shared.queryBuilder().buildObject(field.getSelectionSet(), tableName, alias);
            String paramName = "del_" + pks.get(0) + "_" + params.size();
            params.put(paramName, shared.filterBuilder().extractValue(idArg.getValue(), variables));
            return "WITH " + alias + " AS (DELETE FROM " + shared.qualifiedTable(tableName)
                    + " WHERE " + shared.dialect().quoteIdentifier(pks.get(0)) + " = :" + paramName
                    + " RETURNING *) SELECT " + objectSql + " FROM " + alias;
        }

        Map<String, Object> inputFields = shared.extractObjectFields(inputArg.getValue(), variables);
        List<String> pks = shared.schemaInfo().getPrimaryKeys(tableName);
        String alias = shared.dialect().randAlias();
        String objectSql = shared.queryBuilder().buildObject(field.getSelectionSet(), tableName, alias);

        Argument idArg = shared.findArg(field, "id");
        if (idArg != null && pks.size() == 1) {
            inputFields.putIfAbsent(pks.get(0), shared.filterBuilder().extractValue(idArg.getValue(), variables));
        }

        List<String> whereClauses = new ArrayList<>();
        for (String pk : pks) {
            Object val = inputFields.get(pk);
            if (val == null) return null;
            String paramName = "del_" + pk + "_" + params.size();
            whereClauses.add(shared.dialect().quoteIdentifier(pk) + " = :" + paramName);
            params.put(paramName, val);
        }

        return "WITH " + alias + " AS (DELETE FROM " + shared.qualifiedTable(tableName)
                + " WHERE " + String.join(" AND ", whereClauses)
                + " RETURNING *) SELECT " + objectSql + " FROM " + alias;
    }

    String compileUpdateCollection(Field field, String tableName, Map<String, Object> params,
                                   Map<String, Object> variables, MutationBuilder shared) {
        Argument setArg = shared.findArg(field, "set");
        if (setArg == null) return null;

        Map<String, Object> setFields = shared.extractObjectFields(setArg.getValue(), variables);
        String alias = shared.dialect().randAlias();
        String objectSql = shared.queryBuilder().buildObject(field.getSelectionSet(), tableName, alias);

        int atMost = 1;
        Argument atMostArg = shared.findArg(field, "atMost");
        if (atMostArg != null) {
            Object val = MutationBuilder.extractValue(atMostArg.getValue(), variables);
            if (val instanceof Number n) atMost = n.intValue();
        }

        List<String> setClauses = new ArrayList<>();
        for (var entry : setFields.entrySet()) {
            String paramName = "uc_set_" + entry.getKey() + "_" + params.size();
            String enumCast = shared.getEnumCastForMutation(tableName, entry.getKey());
            setClauses.add(shared.dialect().quoteIdentifier(entry.getKey()) + " = :" + paramName + enumCast);
            params.put(paramName, entry.getValue());
        }

        String innerAlias = shared.dialect().quoteIdentifier("__inner");
        StringBuilder filterWhere = new StringBuilder();
        Argument filterArg = shared.findArg(field, "filter");
        if (filterArg != null && filterArg.getValue() instanceof ObjectValue filterOv) {
            List<String> conditions = new ArrayList<>();
            shared.filterBuilder().buildFilterConditions(filterOv, innerAlias, params, conditions, tableName);
            if (!conditions.isEmpty()) {
                filterWhere.append(" WHERE ").append(String.join(" AND ", conditions));
            }
        }

        String atMostParam = "uc_atmost_" + params.size();
        params.put(atMostParam, atMost);

        return "WITH " + alias + " AS (UPDATE " + shared.qualifiedTable(tableName)
                + " SET " + String.join(", ", setClauses)
                + " WHERE ctid IN (SELECT ctid FROM " + shared.qualifiedTable(tableName)
                + " " + innerAlias + filterWhere + " LIMIT :" + atMostParam + ")"
                + " RETURNING *) SELECT " + shared.dialect().coalesceArray(shared.dialect().aggregateArray(objectSql)) + " FROM " + alias;
    }

    String compileDeleteFromCollection(Field field, String tableName, Map<String, Object> params,
                                       Map<String, Object> variables, MutationBuilder shared) {
        String alias = shared.dialect().randAlias();
        String objectSql = shared.queryBuilder().buildObject(field.getSelectionSet(), tableName, alias);

        int atMost = 1;
        Argument atMostArg = shared.findArg(field, "atMost");
        if (atMostArg != null) {
            Object val = MutationBuilder.extractValue(atMostArg.getValue(), variables);
            if (val instanceof Number n) atMost = n.intValue();
        }

        String innerAlias = shared.dialect().quoteIdentifier("__inner");
        StringBuilder filterWhere = new StringBuilder();
        Argument filterArg = shared.findArg(field, "filter");
        if (filterArg != null && filterArg.getValue() instanceof ObjectValue filterOv) {
            List<String> conditions = new ArrayList<>();
            shared.filterBuilder().buildFilterConditions(filterOv, innerAlias, params, conditions, tableName);
            if (!conditions.isEmpty()) {
                filterWhere.append(" WHERE ").append(String.join(" AND ", conditions));
            }
        }

        String atMostParam = "dc_atmost_" + params.size();
        params.put(atMostParam, atMost);

        return "WITH " + alias + " AS (DELETE FROM " + shared.qualifiedTable(tableName)
                + " WHERE ctid IN (SELECT ctid FROM " + shared.qualifiedTable(tableName)
                + " " + innerAlias + filterWhere + " LIMIT :" + atMostParam + ")"
                + " RETURNING *) SELECT " + shared.dialect().coalesceArray(shared.dialect().aggregateArray(objectSql)) + " FROM " + alias;
    }

    String wrapMutationResult(String mutationSql, String fieldName) {
        int selectIdx = mutationSql.lastIndexOf(") SELECT ");
        if (selectIdx == -1) return mutationSql;
        String ctePart = mutationSql.substring(0, selectIdx + 1);
        String selectPart = mutationSql.substring(selectIdx + 2);
        return ctePart + " SELECT jsonb_build_object('" + fieldName + "', (" + selectPart + "))";
    }
}
