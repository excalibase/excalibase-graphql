package io.github.excalibase.permissions;

/** The right-hand side of a column comparison: a literal, or a session variable bound per request. */
public sealed interface Operand permits Literal, SessionVariable {
}
