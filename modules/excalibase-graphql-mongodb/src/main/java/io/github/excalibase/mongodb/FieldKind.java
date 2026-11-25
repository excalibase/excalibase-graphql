package io.github.excalibase.mongodb;

import io.github.excalibase.rls.FieldType;

import java.util.Locale;
import java.util.Set;

/** The type a collection declares for a field. Documents carry no schema, so the declaration is the truth. */
public enum FieldKind {
    STRING(Set.of(FieldType.STRING)),
    OBJECT_ID(Set.of(FieldType.STRING)),
    INT(Set.of(FieldType.INTEGER)),
    LONG(Set.of(FieldType.LONG, FieldType.INTEGER)),
    DOUBLE(Set.of(FieldType.DOUBLE, FieldType.DECIMAL, FieldType.INTEGER, FieldType.LONG)),
    BOOLEAN(Set.of(FieldType.BOOLEAN)),
    DATETIME(Set.of(FieldType.DATETIME, FieldType.DATE));

    private final Set<FieldType> ruleTypes;

    FieldKind(Set<FieldType> ruleTypes) {
        this.ruleTypes = ruleTypes;
    }

    /** The rule field types whose resolved values are comparable with this kind without a silent mismatch. */
    public boolean accepts(FieldType ruleType) {
        return ruleTypes.contains(ruleType);
    }

    public static FieldKind parse(String declared) {
        String normalised = declared == null ? "" : declared.trim().replace("_", "").toLowerCase(Locale.ROOT);
        return switch (normalised) {
            case "string" -> STRING;
            case "objectid" -> OBJECT_ID;
            case "int", "integer" -> INT;
            case "long" -> LONG;
            case "double", "float" -> DOUBLE;
            case "boolean", "bool" -> BOOLEAN;
            case "datetime", "date", "timestamp" -> DATETIME;
            default -> throw new IllegalStateException("Unknown field type \"" + declared + "\"");
        };
    }
}
