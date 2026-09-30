package io.github.excalibase.permissions;

import java.util.Map;
import java.util.Objects;

public record InsertPermission(BoolExp check, ColumnSet columns, Map<String, PresetValue> set) {

    public InsertPermission {
        Objects.requireNonNull(check, "check");
        Objects.requireNonNull(columns, "columns");
        set = Map.copyOf(set);
    }
}
