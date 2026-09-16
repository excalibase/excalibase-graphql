package io.github.excalibase.security;

/**
 * Thrown when no grant exposes a table/operation to the caller. Raised before
 * any SQL is built for that table, so the request never reaches the database.
 *
 * <p>Extends {@link RlsViolationException} to reuse the denial plumbing the
 * GraphQL and REST surfaces already have, but reports its own
 * {@value #CODE} so clients can tell "this table is not exposed to you" apart
 * from "the rows you asked for are not yours".
 */
public class GrantDeniedException extends RlsViolationException {

    public static final String CODE = "GRANT_DENIED";

    public GrantDeniedException(String operation, String table) {
        super(operation, table);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String getMessage() {
        return "No grant exposes " + operation() + " on " + table();
    }
}
