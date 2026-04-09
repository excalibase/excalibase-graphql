package io.github.excalibase.postgres;

import graphql.language.*;
import io.github.excalibase.compiler.MutationBuilder;
import io.github.excalibase.spi.MutationCompiler;
import io.github.excalibase.compiler.SqlCompiler;

import java.util.*;
import static io.github.excalibase.schema.GraphqlConstants.*;

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
        if (fieldName.startsWith(UPDATE_PREFIX) && fieldName.endsWith(COLLECTION_SUFFIX)) {
            String typePart = fieldName.substring(UPDATE_PREFIX.length(), fieldName.length() - COLLECTION_SUFFIX.length());
            String tableName = shared.resolveMutationTable(typePart);
            if (tableName != null) sql = compileUpdateCollection(field, tableName, params, variables, shared);
        } else if (fieldName.startsWith(DELETE_FROM_PREFIX) && fieldName.endsWith(COLLECTION_SUFFIX)) {
            String typePart = fieldName.substring(DELETE_FROM_PREFIX.length(), fieldName.length() - COLLECTION_SUFFIX.length());
            String tableName = shared.resolveMutationTable(typePart);
            if (tableName != null) sql = compileDeleteFromCollection(field, tableName, params, variables, shared);
        } else if (fieldName.startsWith(CREATE_MANY_PREFIX)) {
            String tableName = shared.resolveMutationTable(fieldName.substring(CREATE_MANY_PREFIX.length()));
            if (tableName != null) sql = compileBulkInsert(field, tableName, params, variables, shared);
        } else if (fieldName.startsWith(CREATE_PREFIX)) {
            String tableName = shared.resolveMutationTable(fieldName.substring(CREATE_PREFIX.length()));
            if (tableName != null) sql = compileInsert(field, tableName, params, variables, shared);
        } else if (fieldName.startsWith(UPDATE_PREFIX)) {
            String tableName = shared.resolveMutationTable(fieldName.substring(UPDATE_PREFIX.length()));
            if (tableName != null) sql = compileUpdate(field, tableName, params, variables, shared);
        } else if (fieldName.startsWith(DELETE_PREFIX)) {
            String tableName = shared.resolveMutationTable(fieldName.substring(DELETE_PREFIX.length()));
            if (tableName != null) sql = compileDelete(field, tableName, params, variables, shared);
        }
        if (sql == null) return null;
        String wrappedSql = shared.dialect().wrapMutationResult(sql, fieldName);
        return new SqlCompiler.CompiledQuery(wrappedSql, params);
    }

    String compileInsert(Field field, String tableName, Map<String, Object> params,
                         Map<String, Object> variables, MutationBuilder shared) {
        Argument inputArg = shared.findArg(field, ARG_INPUT);
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
        Argument onConflictArg = shared.findArg(field, ARG_ON_CONFLICT);
        String onConflictSql = "";
        if (onConflictArg != null && onConflictArg.getValue() instanceof ObjectValue ocOv) {
            String constraint = null;
            List<String> updateCols = new ArrayList<>();
            for (ObjectField of : ocOv.getObjectFields()) {
                if (ON_CONFLICT_CONSTRAINT.equals(of.getName())) {
                    constraint = shared.filterBuilder().extractValue(of.getValue()).toString();
                } else if (ON_CONFLICT_UPDATE_COLUMNS.equals(of.getName()) && of.getValue() instanceof ArrayValue av) {
                    for (Value<?> v : av.getValues()) {
                        updateCols.add(shared.filterBuilder().extractValue(v).toString());
                    }
                }
            }
            if (constraint != null && !updateCols.isEmpty()) {
                onConflictSql = " " + shared.dialect().onConflict(List.of(constraint), updateCols);
            }
        }

        return shared.dialect().cteInsert(alias, shared.qualifiedTable(tableName),
                String.join(", ", cols), String.join(", ", vals), onConflictSql, objectSql);
    }

    String compileBulkInsert(Field field, String tableName, Map<String, Object> params,
                             Map<String, Object> variables, MutationBuilder shared) {
        Argument inputsArg = shared.findArg(field, ARG_INPUTS);
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

        return shared.dialect().cteBulkInsert(alias, shared.qualifiedTable(tableName),
                String.join(", ", colsSql), String.join(", ", valueRows), objectSql);
    }

    String compileUpdate(Field field, String tableName, Map<String, Object> params,
                         Map<String, Object> variables, MutationBuilder shared) {
        Argument inputArg = shared.findArg(field, ARG_INPUT);
        if (inputArg == null) return null;

        Map<String, Object> inputFields = shared.extractObjectFields(inputArg.getValue(), variables);
        String alias = shared.dialect().randAlias();
        String objectSql = shared.queryBuilder().buildObject(field.getSelectionSet(), tableName, alias);

        List<String> setClauses = new ArrayList<>();
        for (var entry : inputFields.entrySet()) {
            String paramName = "upd_" + entry.getKey() + "_" + params.size();
            String enumCast = shared.getEnumCastForMutation(tableName, entry.getKey());
            setClauses.add(shared.dialect().quoteIdentifier(entry.getKey()) + " = :" + paramName + enumCast);
            params.put(paramName, entry.getValue());
        }
        if (setClauses.isEmpty()) return null;

        // Build WHERE from where argument (filter-based, same as queries)
        StringBuilder whereSql = new StringBuilder();
        shared.filterBuilder().applyWhere(whereSql, field, alias, params, tableName);
        if (whereSql.isEmpty()) return null; // Require where to prevent accidental full-table update

        return shared.dialect().cteUpdate(alias, shared.qualifiedTable(tableName),
                String.join(", ", setClauses), whereSql.toString(), objectSql);
    }

    String compileDelete(Field field, String tableName, Map<String, Object> params,
                         Map<String, Object> variables, MutationBuilder shared) {
        String alias = shared.dialect().randAlias();
        String objectSql = shared.queryBuilder().buildObject(field.getSelectionSet(), tableName, alias);

        // Build WHERE from where argument (filter-based, same as queries)
        StringBuilder whereSql = new StringBuilder();
        shared.filterBuilder().applyWhere(whereSql, field, alias, params, tableName);
        if (whereSql.isEmpty()) return null; // Require where to prevent accidental full-table delete

        return shared.dialect().cteDelete(alias, shared.qualifiedTable(tableName),
                whereSql.toString(), objectSql);
    }

    String compileUpdateCollection(Field field, String tableName, Map<String, Object> params,
                                   Map<String, Object> variables, MutationBuilder shared) {
        Argument setArg = shared.findArg(field, ARG_SET);
        if (setArg == null) return null;

        Map<String, Object> setFields = shared.extractObjectFields(setArg.getValue(), variables);
        String alias = shared.dialect().randAlias();
        String objectSql = shared.queryBuilder().buildObject(field.getSelectionSet(), tableName, alias);

        int atMost = 1;
        Argument atMostArg = shared.findArg(field, ARG_AT_MOST);
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
        Argument filterArg = shared.findArg(field, ARG_FILTER);
        if (filterArg != null && filterArg.getValue() instanceof ObjectValue filterOv) {
            List<String> conditions = new ArrayList<>();
            shared.filterBuilder().buildFilterConditions(filterOv, innerAlias, params, conditions, tableName);
            if (!conditions.isEmpty()) {
                filterWhere.append(" WHERE ").append(String.join(" AND ", conditions));
            }
        }

        String atMostParam = "uc_atmost_" + params.size();
        params.put(atMostParam, atMost);

        String ctidWhere = " WHERE ctid IN (SELECT ctid FROM " + shared.qualifiedTable(tableName)
                + " " + innerAlias + filterWhere + " LIMIT :" + atMostParam + ")";
        return shared.dialect().cteUpdate(alias, shared.qualifiedTable(tableName),
                String.join(", ", setClauses), ctidWhere, objectSql);
    }

    String compileDeleteFromCollection(Field field, String tableName, Map<String, Object> params,
                                       Map<String, Object> variables, MutationBuilder shared) {
        String alias = shared.dialect().randAlias();
        String objectSql = shared.queryBuilder().buildObject(field.getSelectionSet(), tableName, alias);

        int atMost = 1;
        Argument atMostArg = shared.findArg(field, ARG_AT_MOST);
        if (atMostArg != null) {
            Object val = MutationBuilder.extractValue(atMostArg.getValue(), variables);
            if (val instanceof Number n) atMost = n.intValue();
        }

        String innerAlias = shared.dialect().quoteIdentifier("__inner");
        StringBuilder filterWhere = new StringBuilder();
        Argument filterArg = shared.findArg(field, ARG_FILTER);
        if (filterArg != null && filterArg.getValue() instanceof ObjectValue filterOv) {
            List<String> conditions = new ArrayList<>();
            shared.filterBuilder().buildFilterConditions(filterOv, innerAlias, params, conditions, tableName);
            if (!conditions.isEmpty()) {
                filterWhere.append(" WHERE ").append(String.join(" AND ", conditions));
            }
        }

        String atMostParam = "dc_atmost_" + params.size();
        params.put(atMostParam, atMost);

        String ctidWhere = " WHERE ctid IN (SELECT ctid FROM " + shared.qualifiedTable(tableName)
                + " " + innerAlias + filterWhere + " LIMIT :" + atMostParam + ")";
        return shared.dialect().cteDelete(alias, shared.qualifiedTable(tableName),
                ctidWhere, objectSql);
    }

}
