package io.github.excalibase.permissions.compile;

import io.github.excalibase.permissions.BoolExp;
import io.github.excalibase.schema.SchemaInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Asks the database what memory cannot decide about one row image: does the row with this primary key
 * exist and pass the filter? Only a row the image identifies completely can be asked about.
 */
public final class RowProbe {

    private RowProbe() {
    }

    /**
     * @return {@code SELECT EXISTS (...)} for the row whose key {@code row} carries, or empty when the
     *         table has no primary key or the image lacks a usable value for one of its columns
     */
    public static Optional<SqlFragment> build(BoolExp filter, String tableKey, Map<String, Object> row,
                                              SchemaInfo schema, SessionBinding binding, ParamNamer namer) {
        if (!schema.hasPrimaryKey(tableKey)) {
            return Optional.empty();
        }
        TableRef table = TableRef.resolve(schema, tableKey);
        String alias = namer.nextAlias();
        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        for (String key : schema.getPrimaryKeys(tableKey)) {
            Optional<Object> value = keyValue(schema, tableKey, key, row.get(key));
            if (value.isEmpty()) {
                return Optional.empty();
            }
            ColumnType type = ColumnType.of(schema, tableKey, key);
            String param = namer.nextParam();
            params.put(param, type.sqlValue(value.get()));
            conditions.add(alias + "." + SqlNames.quote(key) + " = " + type.placeholder(param));
        }
        SqlFragment matches = FilterCompiler.compile(filter, tableKey, alias, schema, binding, namer);
        params.putAll(matches.params());
        return Optional.of(new SqlFragment("SELECT EXISTS (SELECT 1 FROM " + table.sql() + " " + alias + " WHERE "
                + String.join(" AND ", conditions) + " AND (" + matches.sql() + "))", params));
    }

    private static Optional<Object> keyValue(SchemaInfo schema, String tableKey, String key, Object raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(ColumnType.of(schema, tableKey, key).canonical(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
