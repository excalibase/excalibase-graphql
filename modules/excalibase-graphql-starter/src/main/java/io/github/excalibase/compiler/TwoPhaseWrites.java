package io.github.excalibase.compiler;

import java.util.List;

/** The root fields of one mutation on a database without {@code RETURNING}, run in order in one transaction. */
public record TwoPhaseWrites(List<Entry> entries) implements TwoPhaseWrite {

    /** One root field: its write, answered under {@code responseKey} (the field's alias, else its name). */
    public record Entry(String responseKey, TwoPhaseWrite write) {
    }
}
