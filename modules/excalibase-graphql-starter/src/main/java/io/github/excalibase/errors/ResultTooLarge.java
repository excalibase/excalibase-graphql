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

    /**
     * MySQL keeps only the first max_error_count warnings of a statement, 1,024 unless a session raises it (which needs
     * a privilege a project's database user does not have). A full list may have dropped the warning that matters, so
     * an answer with no rows beside it cannot be confirmed complete.
     */
    public static final int MAX_ERROR_COUNT = 1024;

    /**
     * Throws the refusal when {@code warning}, the first of a statement's warnings or null, says a result was cut, or
     * when the answer is missing and the list is so full that the warning may have been dropped from it. A full list
     * beside a real answer, or beside no answer at all (a write), is not a refusal.
     */
    public static void refuseIfCut(SQLWarning warning, boolean answerMissing) {
        int count = 0;
        for (SQLWarning current = warning; current != null; current = current.getNextWarning()) {
            count++;
            if (current.getErrorCode() == PACKET_OVERFLOWED) {
                throw new DataErrorException(DataError.resultTooLarge());
            }
        }
        if (answerMissing && count >= MAX_ERROR_COUNT) {
            throw new DataErrorException(DataError.resultUnconfirmed());
        }
    }
}
