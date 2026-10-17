package io.github.excalibase.compiler;

import java.util.List;
import java.util.Set;

/** The root fields of one mutation on a database without {@code RETURNING}, run in order in one transaction. */
public record TwoPhaseWrites(List<Entry> entries) implements TwoPhaseWrite {

    /** One root field's write. */
    public interface Write extends TwoPhaseWrite {

        /** The table it changes. */
        String table();

        /** The tables its permission check reads beyond the written row; judged after the write, they must be unwritten. */
        Set<String> checkReads();
    }

    /** One root field: its write, answered under {@code responseKey} (the field's alias, else its name). */
    public record Entry(String responseKey, Write write) {
    }
}
