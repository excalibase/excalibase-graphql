package io.github.excalibase.compiler;

import java.util.Set;

/** A GraphQL mutation field on a database without RETURNING: its rows are written and read back by key, as REST's are. */
public record KeyedTableWrite(String table, Set<String> checkReads, KeyedWrite write) implements TwoPhaseWrites.Write {

    /** This write, run as {@code run}. */
    KeyedTableWrite with(KeyedWrite run) {
        return new KeyedTableWrite(table, checkReads, run);
    }
}
