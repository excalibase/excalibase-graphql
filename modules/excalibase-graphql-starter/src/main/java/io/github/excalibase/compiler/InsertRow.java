package io.github.excalibase.compiler;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One row of an insert as the client sent it: the values of its columns, and the rows it nests through
 * relationships ({@code relationship: { data: ... }}), in the order they were sent.
 */
public record InsertRow(Map<String, Object> columns, List<Nested> nested) {

    public InsertRow {
        columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns));
        nested = List.copyOf(nested);
    }

    /** True when the row nests no other row. */
    public boolean flat() {
        return nested.isEmpty();
    }

    /** Rows nested through one relationship: several for an array relationship, one for an object one. */
    public record Nested(String relationship, boolean array, List<InsertRow> rows) {
        public Nested {
            rows = List.copyOf(rows);
        }
    }
}
