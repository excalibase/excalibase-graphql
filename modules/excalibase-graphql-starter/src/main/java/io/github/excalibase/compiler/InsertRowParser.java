package io.github.excalibase.compiler;

import graphql.language.ArrayValue;
import graphql.language.NullValue;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import graphql.language.Value;
import graphql.language.VariableReference;
import io.github.excalibase.schema.SchemaInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the rows of an insert, from a literal or from variables, splitting each into its columns and the
 * rows it nests through the role's insert relationships ({@code relationship: { data: ... }}). A name that
 * is no insert relationship of the table stays a column, so a relationship the role cannot insert through
 * is refused like any column it may not set. As in Hasura, a {@code null} relationship or an empty
 * {@code data} list nests nothing.
 */
public final class InsertRowParser {

    private static final String DATA = "data";

    private final SchemaInfo schema;
    private final Map<String, Object> variables;

    public InsertRowParser(SchemaInfo schema, Map<String, Object> variables) {
        this.schema = schema;
        this.variables = variables == null ? Map.of() : variables;
    }

    /** One row: an object literal or a variable holding one. */
    public InsertRow row(String table, Value<?> value) {
        return rowOf(table, value);
    }

    /** Rows: a list literal or a variable holding one. */
    public List<InsertRow> rows(String table, Value<?> value) {
        return rowsOf(table, value);
    }

    private InsertRow rowOf(String table, Object value) {
        Object resolved = resolve(value);
        if (resolved instanceof ObjectValue object) {
            return literalRow(table, object);
        }
        if (resolved instanceof Map<?, ?> map) {
            return variableRow(table, map);
        }
        throw new IllegalArgumentException("Each row inserted into " + table + " must be an object");
    }

    private List<InsertRow> rowsOf(String table, Object value) {
        Object resolved = resolve(value);
        List<?> elements = switch (resolved) {
            case ArrayValue array -> array.getValues();
            case List<?> list -> list;
            case null, default -> throw new IllegalArgumentException("The rows inserted into " + table
                    + " must be a list");
        };
        return elements.stream().map(element -> rowOf(table, element)).toList();
    }

    private InsertRow literalRow(String table, ObjectValue object) {
        Map<String, Object> columns = new LinkedHashMap<>();
        List<InsertRow.Nested> nested = new ArrayList<>();
        for (ObjectField field : object.getObjectFields()) {
            if (isRelationship(table, field.getName())) {
                nest(table, field.getName(), resolve(field.getValue()), nested);
            } else {
                columns.put(field.getName(), MutationBuilder.extractValue(field.getValue(), variables));
            }
        }
        return new InsertRow(columns, nested);
    }

    private InsertRow variableRow(String table, Map<?, ?> map) {
        Map<String, Object> columns = new LinkedHashMap<>();
        List<InsertRow.Nested> nested = new ArrayList<>();
        map.forEach((key, value) -> {
            String name = key.toString();
            if (isRelationship(table, name)) {
                nest(table, name, value, nested);
            } else {
                columns.put(name, value);
            }
        });
        return new InsertRow(columns, nested);
    }

    /** A column of the table wins over a relationship of the same name, as in the insert input. */
    private boolean isRelationship(String table, String name) {
        return schema.getColumnType(table, name) == null
                && (schema.getArrayInsertRelationship(table, name) != null
                    || schema.getObjectInsertRelationship(table, name) != null);
    }

    private void nest(String table, String relationship, Object value, List<InsertRow.Nested> nested) {
        if (value == null || value instanceof NullValue) {
            return;
        }
        Object data = data(table, relationship, value);
        SchemaInfo.ReverseFkInfo array = schema.getArrayInsertRelationship(table, relationship);
        if (array != null) {
            List<InsertRow> rows = rowsOf(array.childTable(), data);
            if (!rows.isEmpty()) {
                nested.add(new InsertRow.Nested(relationship, true, rows));
            }
            return;
        }
        String target = schema.getObjectInsertRelationship(table, relationship).refTable();
        nested.add(new InsertRow.Nested(relationship, false, List.of(rowOf(target, data))));
    }

    /** The {@code data} of a relationship input, its only field. */
    private Object data(String table, String relationship, Object value) {
        Map<String, Object> fields = new LinkedHashMap<>();
        switch (value) {
            case ObjectValue object ->
                    object.getObjectFields().forEach(field -> fields.put(field.getName(), resolve(field.getValue())));
            case Map<?, ?> map -> map.forEach((key, field) -> fields.put(key.toString(), field));
            default -> throw new IllegalArgumentException("Relationship '" + relationship + "' in the input of "
                    + table + " must be an object with 'data'");
        }
        fields.keySet().stream().filter(name -> !DATA.equals(name)).findFirst().ifPresent(name -> {
            throw new IllegalArgumentException("Unknown field '" + name + "' in relationship '" + relationship
                    + "' of " + table);
        });
        Object data = fields.get(DATA);
        if (data == null || data instanceof NullValue) {
            throw new IllegalArgumentException("Relationship '" + relationship + "' in the input of " + table
                    + " requires 'data'");
        }
        return data;
    }

    /** A variable reference stands for its value; anything else is itself. */
    private Object resolve(Object value) {
        return value instanceof VariableReference reference ? variables.get(reference.getName()) : value;
    }
}
