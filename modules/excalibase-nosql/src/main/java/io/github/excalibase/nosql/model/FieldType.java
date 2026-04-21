package io.github.excalibase.nosql.model;

public enum FieldType {
    STRING, NUMBER, BOOLEAN, OBJECT, ARRAY, VECTOR;

    public static FieldType from(String typeName) {
        if (typeName == null) return STRING;
        return switch (typeName.toLowerCase()) {
            case "number", "numeric", "int", "integer", "float", "double" -> NUMBER;
            case "boolean", "bool" -> BOOLEAN;
            case "object", "json" -> OBJECT;
            case "array" -> ARRAY;
            case "vector" -> VECTOR;
            default -> STRING;
        };
    }
}
