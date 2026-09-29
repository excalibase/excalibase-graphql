package io.github.excalibase.permissions;

import java.util.Arrays;
import java.util.Optional;

/** Column comparison operators of the boolean expression grammar (docs/features/permissions.md §4). */
public enum ComparisonOperator {
    EQ("_eq"),
    NEQ("_neq"),
    GT("_gt"),
    GTE("_gte"),
    LT("_lt"),
    LTE("_lte"),
    IN("_in"),
    NIN("_nin"),
    LIKE("_like"),
    NLIKE("_nlike"),
    IS_NULL("_is_null");

    private final String jsonName;

    ComparisonOperator(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }

    public static Optional<ComparisonOperator> fromJsonName(String name) {
        return Arrays.stream(values()).filter(operator -> operator.jsonName.equals(name)).findFirst();
    }
}
