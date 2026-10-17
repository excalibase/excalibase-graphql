package io.github.excalibase.compiler;

/**
 * A compiled write for a database without {@code RETURNING}. Only the dialect's own
 * {@link io.github.excalibase.spi.MutationExecutor} knows its shape and runs it.
 */
public interface TwoPhaseWrite {
}
