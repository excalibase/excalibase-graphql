package io.github.excalibase.security;

/**
 * The database operation a WHERE/row-check contribution is being requested for.
 * Mirrors the RLS engine's {@code Operation} enum but lives in the starter
 * module so the generic compiler can name an operation without depending on
 * the engine. The api-layer contributor maps these to engine operations.
 */
public enum RlsOp {
    SELECT,
    INSERT,
    UPDATE,
    DELETE
}
