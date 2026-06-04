package io.github.excalibase.rls.jdbc;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The CLS counterpart to {@link SqlFilter}. Carries the rewritten SELECT
 * list (with masking expressions injected per RFC 0007), the bind params
 * for any literal values in those expressions, and the set of columns
 * fully hidden so the consumer can drop them from its response shape /
 * GraphQL field selection.
 *
 * <p>v1.6 emits HIDE (drop entirely) and NULL ({@code NULL AS "col"}).
 * v1.7 extends to PARTIAL / HASH / CUSTOM via a {@code SqlDialect}
 * strategy.
 */
public record SqlProjection(
    List<String> selectList,
    Map<String, Object> params,
    Set<String> hidden
) {

    public SqlProjection {
        selectList = (selectList == null) ? List.of() : List.copyOf(selectList);
        params = (params == null || params.isEmpty())
            ? Map.of()
            : Collections.unmodifiableMap(new HashMap<>(params));
        hidden = (hidden == null) ? Set.of() : Set.copyOf(hidden);
    }

    /**
     * Returns the comma-separated SELECT list ready to drop into
     * {@code "SELECT " + composeSelect() + " FROM ..."}. Empty when every
     * requested column was hidden, or when no columns were requested.
     */
    public String composeSelect() {
        return String.join(", ", selectList);
    }

    /** Convenience: true iff the result of {@link #composeSelect()} would be empty. */
    public boolean isEmpty() {
        return selectList.isEmpty();
    }

    public static SqlProjection empty() {
        return new SqlProjection(List.of(), Map.of(), Set.of());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SqlProjection that)) return false;
        return Objects.equals(selectList, that.selectList)
            && Objects.equals(params, that.params)
            && Objects.equals(hidden, that.hidden);
    }
}
