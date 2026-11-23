package io.github.excalibase.errors;

import java.sql.SQLWarning;

/**
 * MySQL's JSON_ARRAYAGG and JSON_OBJECT give NULL, with a warning only, when their result passes max_allowed_packet,
 * which reads exactly like "no rows". The warning is the one sign of it, so a statement's warnings are read and the
 * request refused, instead of answered empty.
 */
public final class ResultTooLarge {

    /** ER_WARN_ALLOWED_PACKET_OVERFLOWED: "Result of json_arrayagg() was larger than max_allowed_packet". */
    private static final int PACKET_OVERFLOWED = 1301;

    private ResultTooLarge() {
    }

    /** Throws the refusal when {@code warning}, the first of a statement's warnings or null, says a result was cut. */
    public static void refuseIfCut(SQLWarning warning) {
        for (SQLWarning current = warning; current != null; current = current.getNextWarning()) {
            if (current.getErrorCode() == PACKET_OVERFLOWED) {
                throw new DataErrorException(DataError.resultTooLarge());
            }
        }
    }
}
