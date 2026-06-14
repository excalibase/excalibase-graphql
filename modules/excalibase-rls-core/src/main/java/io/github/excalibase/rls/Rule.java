package io.github.excalibase.rls;

import java.util.Objects;

public record Rule(
    String field,
    FieldType fieldType,
    RuleOperator operator,
    String value
) {
    public Rule {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(fieldType, "fieldType");
        Objects.requireNonNull(operator, "operator");
    }
}
