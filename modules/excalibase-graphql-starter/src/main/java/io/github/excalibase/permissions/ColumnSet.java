package io.github.excalibase.permissions;

import java.util.List;

/** The columns a permission covers: every column ({@code "*"}, including ones added later) or a list. */
public record ColumnSet(boolean all, List<String> names) {

    public static final ColumnSet ALL = new ColumnSet(true, List.of());

    public ColumnSet {
        names = List.copyOf(names);
        if (all && !names.isEmpty()) {
            throw new IllegalArgumentException("\"*\" takes no column list");
        }
    }

    public static ColumnSet of(List<String> names) {
        return new ColumnSet(false, names);
    }

    public boolean permits(String column) {
        return all || names.contains(column);
    }
}
