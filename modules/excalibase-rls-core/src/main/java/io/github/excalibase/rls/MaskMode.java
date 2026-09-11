package io.github.excalibase.rls;

/**
 * What should happen to a column when an in-scope {@link ColumnPolicy}
 * applies to it.
 *
 * <p>v1.6 implements {@link #HIDE} and {@link #NULL}; the remaining modes
 * are reserved and will throw on use until v1.7 ships the dialect-aware
 * SQL emission and Java fallback for each.
 */
public enum MaskMode {
    /** Column is omitted from the result entirely. */
    HIDE,
    /** Column value is replaced with null. */
    NULL,
    /** Column value is partially masked per a {@link PartialMaskSpec}. */
    PARTIAL,
    /** Column value is replaced with SHA-256 hex of the original. */
    HASH,
    /** Caller-registered transformation looked up by ColumnPolicy.customMaskerKey. */
    CUSTOM
}
