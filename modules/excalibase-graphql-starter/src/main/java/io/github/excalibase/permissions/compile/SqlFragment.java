package io.github.excalibase.permissions.compile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A boolean SQL expression to AND into a WHERE clause, with its named bind parameters
 * ({@code :name}). A value may be null: a session array can carry an SQL NULL element.
 */
public record SqlFragment(String sql, Map<String, Object> params) {

    public SqlFragment {
        Objects.requireNonNull(sql, "sql");
        params = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(params, "params")));
    }
}
