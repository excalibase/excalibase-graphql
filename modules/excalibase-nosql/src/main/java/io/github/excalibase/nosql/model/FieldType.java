package io.github.excalibase.nosql.model;

public enum FieldType {
    STRING, NUMBER, BOOLEAN, OBJECT, ARRAY, VECTOR;

    public static FieldType from(String s) {
        if (s == null) return STRING;
        return switch (s.toLowerCase()) {
            case "number", "numeric", "int", "integer", "float", "double" -> NUMBER;
            case "boolean", "bool" -> BOOLEAN;
            case "object", "json" -> OBJECT;
            case "array" -> ARRAY;
            case "vector" -> VECTOR;
            default -> STRING;
        };
    }
}
