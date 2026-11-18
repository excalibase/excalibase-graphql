package io.github.excalibase.admin;

import io.github.excalibase.schema.ReservedSchemas;
import io.github.excalibase.schema.SchemaInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The reflected schema as the console shows it. A reserved schema is left out by the same check every other path
 * applies ({@link ReservedSchemas}), so the engine's own tables and functions never appear here, even if
 * reflection handed them over: a function is also left out when it returns rows of a reserved table or takes one.
 */
final class AdminSchemaView {

    private AdminSchemaView() {
    }

    static Map<String, Object> of(SchemaInfo schema, ReservedSchemas reserved) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("tables", tables(schema, reserved));
        view.put("functions", functions(schema, reserved));
        return view;
    }

    private static List<Map<String, Object>> tables(SchemaInfo schema, ReservedSchemas reserved) {
        List<Map<String, Object>> tables = new ArrayList<>();
        schema.getTableNames().stream().sorted().forEach(key -> {
            String schemaName = schemaOf(schema, key);
            if (!reserved.isReserved(schemaName)) {
                tables.add(table(schema, key, schemaName));
            }
        });
        return tables;
    }

    private static Map<String, Object> table(SchemaInfo schema, String key, String schemaName) {
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("schema", schemaName);
        table.put("name", key.startsWith(schemaName + ".") ? key.substring(schemaName.length() + 1) : key);
        table.put("kind", schema.isView(key) ? "view" : "table");
        List<String> primaryKeys = schema.getPrimaryKeys(key);
        List<Map<String, Object>> columns = new ArrayList<>();
        for (String column : schema.getColumns(key)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", column);
            entry.put("type", schema.getColumnType(key, column));
            entry.put("primaryKey", primaryKeys.contains(column));
            columns.add(entry);
        }
        table.put("columns", columns);
        return table;
    }

    private static String schemaOf(SchemaInfo schema, String key) {
        String schemaName = schema.getTableSchema(key);
        if (schemaName != null) {
            return schemaName;
        }
        int dot = key.indexOf('.');
        return dot > 0 ? key.substring(0, dot) : "";
    }

    private static List<Map<String, Object>> functions(SchemaInfo schema, ReservedSchemas reserved) {
        return schema.getFunctions().values().stream()
                .filter(function -> !reserved.isReserved(function.schema()))
                .filter(function -> !reserved.isReservedObject(function.returnTable()))
                .filter(function -> function.args().stream().noneMatch(arg -> reserved.isReservedObject(arg.type())))
                .sorted(Comparator.comparing(SchemaInfo.FunctionInfo::qualifiedName))
                .map(AdminSchemaView::function)
                .toList();
    }

    private static Map<String, Object> function(SchemaInfo.FunctionInfo function) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("schema", function.schema());
        entry.put("name", function.name());
        entry.put("kind", function.kind().name().toLowerCase(Locale.ROOT));
        entry.put("returnsSet", function.returnsSet());
        entry.put("returnTable", function.returnTable());
        entry.put("args", function.args().stream().map(arg -> {
            Map<String, Object> declared = new LinkedHashMap<>();
            declared.put("name", arg.name());
            declared.put("type", arg.type());
            declared.put("mode", arg.mode());
            return declared;
        }).toList());
        return entry;
    }
}
