package io.github.excalibase.permissions;

import java.util.Map;
import java.util.Objects;

public record UpdatePermission(BoolExp filter, BoolExp check, ColumnSet columns, Map<String, PresetValue> set) {

    public UpdatePermission {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(check, "check");
        Objects.requireNonNull(columns, "columns");
        set = Map.copyOf(set);
    }
}
