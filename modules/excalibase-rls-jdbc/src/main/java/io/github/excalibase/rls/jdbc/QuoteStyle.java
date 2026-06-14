package io.github.excalibase.rls.jdbc;

/**
 * SQL identifier quoting dialect for emitted RLS predicates. Identifiers are
 * pre-validated by {@link SqlIdentifier#checkColumn} (no quote characters can
 * appear), so quoting is a simple wrap — but the wrap character differs per
 * engine, and getting it wrong is silent, not loud: a backtick column written
 * as {@code "col"} on MySQL becomes a string-literal comparison that quietly
 * matches nothing instead of failing.
 */
public enum QuoteStyle {
    /** Standard SQL double quotes — Postgres, H2, etc. The default. */
    ANSI('"'),
    /** MySQL / MariaDB backticks. */
    BACKTICK('`');

    private final char q;

    QuoteStyle(char q) {
        this.q = q;
    }

    /** Wraps an already-validated identifier in this dialect's quote character. */
    public String quote(String validatedIdentifier) {
        return q + validatedIdentifier + q;
    }
}
