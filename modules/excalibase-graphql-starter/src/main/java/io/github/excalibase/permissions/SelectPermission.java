package io.github.excalibase.permissions;

import java.util.Objects;

/** @param limit the most rows one select returns for the role, or null for no cap of its own */
public record SelectPermission(BoolExp filter, ColumnSet columns, Integer limit, boolean allowAggregations) {

    public SelectPermission {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(columns, "columns");
        if (limit != null && limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }
}
