package io.github.excalibase.postgres;

import graphql.language.*;
import io.github.excalibase.compiler.MutationBuilder;
import io.github.excalibase.spi.MutationCompiler;
import io.github.excalibase.compiler.SqlCompiler;

import java.util.*;
import static io.github.excalibase.schema.GraphqlConstants.*;
import static io.github.excalibase.compiler.SqlKeywords.*;
import static io.github.excalibase.compiler.SqlKeywords.*;

/**
 * PostgreSQL mutation compiler. Uses CTE + RETURNING pattern.
 */
public class PostgresMutationCompiler implements MutationCompiler {

    public PostgresMutationCompiler() {}

    @Override
    public SqlCompiler.CompiledQuery compileMutation(Field field, String fieldName,
                                                     Map<String, Object> params, Map<String, Object> variables,
                                                     MutationBuilder shared) {
        String sql = routeMutation(field, fieldName, params, variables, shared);
        if (sql == null) return null;
        return new SqlCompiler.CompiledQuery(shared.dialect().wrapMutationResult(sql, fieldName), params);
    }

    private String routeMutation(Field field, String fieldName,
                                 Map<String, Object> params, Map<String, Object> variables,
                                 MutationBuilder shared) {
        if (fieldName.startsWith(UPDATE_PREFIX) && fieldName.endsWith(COLLECTION_SUFFIX)) {
            String typePart = fieldName.substring(UPDATE_PREFIX.length(), fieldName.length() - COLLECTION_SUFFIX.length());
            String tableName = shared.resolveMutationTable(typePart);
            return tableName != null ? compileUpdateCollection(field, tableName, params, variables, shared) : null;
        }
        if (fieldName.startsWith(DELETE_FROM_PREFIX) && fieldName.endsWith(COLLECTION_SUFFIX)) {
            String typePart = fieldName.substring(DELETE_FROM_PREFIX.length(), fieldName.length() - COLLECTION_SUFFIX.length());
            String tableName = shared.resolveMutationTable(typePart);
            return tableName != null ? compileDeleteFromCollection(field, tableName, params, variables, shared) : null;
        }
        if (fieldName.startsWith(CREATE_MANY_PREFIX)) {
            String tableName = shared.resolveMutationTable(fieldName.substring(CREATE_MANY_PREFIX.length()));
            return tableName != null ? compileBulkInsert(field, tableName, params, variables, shared) : null;
        }
        if (fieldName.startsWith(CREATE_PREFIX)) {
            String tableName = shared.resolveMutationTable(fieldName.substring(CREATE_PREFIX.length()));
            return tableName != null ? compileInsert(field, tableName, params, variables, shared) : null;
        }
        if (fieldName.startsWith(UPDATE_PREFIX)) {
            String tableName = shared.resolveMutationTable(fieldName.substring(UPDATE_PREFIX.length()));
            return tableName != null ? compileUpdate(field, tableName, params, variables, shared) : null;
        }
        if (fieldName.startsWith(DELETE_PREFIX)) {
            String tableName = shared.resolveMutationTable(fieldName.substring(DELETE_PREFIX.length()));
            return tableName != null ? compileDelete(field, tableName, params, variables, shared) : null;
        }
        return null;
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
            String paramName = namedParam(P_INSERT, entry.getKey(), params.size());
            String enumCast = shared.getEnumCastForMutation(tableName, entry.getKey());
            Object value = shared.convertCompositeValue(tableName, entry.getKey(), entry.getValue());
            vals.add(param(paramName) + enumCast);
            params.put(paramName, value);
        }

        String onConflictSql = parseOnConflict(field, shared);

        return shared.dialect().cteInsert(alias, shared.qualifiedTable(tableName),
                joinCols(cols), joinCols(vals), onConflictSql, objectSql);
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
                String paramName = namedParam(P_BULK_INSERT, col + "_" + i, params.size());
                String enumCast = shared.getEnumCastForMutation(tableName, col);
                vals.add(param(paramName) + enumCast);
                params.put(paramName, row.get(col));
            }
            valueRows.add(parens(joinCols(vals)));
        }

        return shared.dialect().cteBulkInsert(alias, shared.qualifiedTable(tableName),
                joinCols(colsSql), joinCols(valueRows), objectSql);
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
            String paramName = namedParam(P_UPDATE, entry.getKey(), params.size());
            String enumCast = shared.getEnumCastForMutation(tableName, entry.getKey());
            setClauses.add(assignWithCast(shared.dialect().quoteIdentifier(entry.getKey()), paramName, enumCast));
            params.put(paramName, entry.getValue());
        }
        if (setClauses.isEmpty()) return null;

        StringBuilder whereSql = new StringBuilder();
        shared.filterBuilder().applyWhere(whereSql, field, alias, params, tableName);
        if (whereSql.isEmpty()) return null;

        return shared.dialect().cteUpdate(alias, shared.qualifiedTable(tableName),
                joinCols(setClauses), whereSql.toString(), objectSql);
    }

    String compileDelete(Field field, String tableName, Map<String, Object> params,
                         Map<String, Object> variables, MutationBuilder shared) {
        String alias = shared.dialect().randAlias();
        String objectSql = shared.queryBuilder().buildObject(field.getSelectionSet(), tableName, alias);

        StringBuilder whereSql = new StringBuilder();
        shared.filterBuilder().applyWhere(whereSql, field, alias, params, tableName);
        if (whereSql.isEmpty()) return null;

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

        int atMost = parseAtMost(field, variables, shared);

        List<String> setClauses = new ArrayList<>();
        for (var entry : setFields.entrySet()) {
            String paramName = namedParam(P_UC_SET, entry.getKey(), params.size());
            String enumCast = shared.getEnumCastForMutation(tableName, entry.getKey());
            setClauses.add(assignWithCast(shared.dialect().quoteIdentifier(entry.getKey()), paramName, enumCast));
            params.put(paramName, entry.getValue());
        }

        String filterWhere = buildCollectionFilter(field, shared, params, tableName);
        String atMostParam = namedParam(P_UC_AT_MOST, params.size());
        params.put(atMostParam, atMost);

        return shared.dialect().cteUpdate(alias, shared.qualifiedTable(tableName),
                joinCols(setClauses),
                ctidSubquery(shared.qualifiedTable(tableName),
                        shared.dialect().quoteIdentifier(INNER_ALIAS), filterWhere, atMostParam),
                objectSql);
    }

    String compileDeleteFromCollection(Field field, String tableName, Map<String, Object> params,
                                       Map<String, Object> variables, MutationBuilder shared) {
        String alias = shared.dialect().randAlias();
        String objectSql = shared.queryBuilder().buildObject(field.getSelectionSet(), tableName, alias);

        int atMost = parseAtMost(field, variables, shared);

        String filterWhere = buildCollectionFilter(field, shared, params, tableName);
        String atMostParam = namedParam(P_DC_AT_MOST, params.size());
        params.put(atMostParam, atMost);

        return shared.dialect().cteDelete(alias, shared.qualifiedTable(tableName),
                ctidSubquery(shared.qualifiedTable(tableName),
                        shared.dialect().quoteIdentifier(INNER_ALIAS), filterWhere, atMostParam),
                objectSql);
    }

    // === Private helpers ===

    private String parseOnConflict(Field field, MutationBuilder shared) {
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
                for (Value<?> v : av.getValues()) {
                    updateCols.add(shared.filterBuilder().extractValue(v).toString());
                }
            }
        }
        if (constraint != null && !updateCols.isEmpty()) {
            return " " + shared.dialect().onConflict(List.of(constraint), updateCols);
        }
        return "";
    }

    private int parseAtMost(Field field, Map<String, Object> variables, MutationBuilder shared) {
        Argument atMostArg = shared.findArg(field, ARG_AT_MOST);
        if (atMostArg != null) {
            Object val = MutationBuilder.extractValue(atMostArg.getValue(), variables);
            if (val instanceof Number n) return n.intValue();
        }
        return 1;
    }

    private String buildCollectionFilter(Field field, MutationBuilder shared,
                                         Map<String, Object> params, String tableName) {
        Argument filterArg = shared.findArg(field, ARG_FILTER);
        if (filterArg != null && filterArg.getValue() instanceof ObjectValue filterOv) {
            String innerAlias = shared.dialect().quoteIdentifier(INNER_ALIAS);
            List<String> conditions = new ArrayList<>();
            shared.filterBuilder().buildFilterConditions(filterOv, innerAlias, params, conditions, tableName);
            if (!conditions.isEmpty()) {
                return WHERE + String.join(AND, conditions);
            }
        }
        return "";
    }
}
