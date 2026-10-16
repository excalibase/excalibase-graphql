package io.github.excalibase.rest.compiler;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** A write run as several statements, on a database without RETURNING, inside the caller's transaction. */
@FunctionalInterface
public interface KeyedWrite {

    /**
     * @param represent whether the written rows are wanted; without it none is read back
     * @return the written rows as JSON (an object for a single insert), or null when not represented
     * @throws IllegalArgumentException when the rows are wanted but cannot be found again
     */
    String run(NamedParameterJdbcTemplate jdbc, boolean represent);
}
