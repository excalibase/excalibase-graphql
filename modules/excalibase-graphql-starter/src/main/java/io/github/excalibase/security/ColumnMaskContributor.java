package io.github.excalibase.security;

/**
 * SPI that decides, per request, how a single column should appear in the
 * GraphQL response — visible, dropped, or nulled. Implemented in
 * {@code excalibase-graphql-api} (which owns the RLS engine) and consulted by
 * the compiler when it builds each table's response object, via
 * {@link RlsContext}. Sibling of {@link RlsWhereContributor}: that one filters
 * <em>rows</em>, this one masks <em>columns</em>.
 */
public interface ColumnMaskContributor {

    /** Per-column visibility verdict. */
    enum Decision {
        /** Emit the column normally. */
        VISIBLE,
        /** Omit the column entirely from the response object (column-level HIDE). */
        HIDDEN,
        /** Emit the column key with a SQL {@code NULL} value (column-level NULL mask). */
        NULLED
    }

    /**
     * @param tableName  the (possibly schema-qualified) table the column belongs to
     * @param columnName the column being projected
     * @return how to render the column; never {@code null} (use {@link Decision#VISIBLE})
     */
    Decision decide(String tableName, String columnName);
}
