package io.github.excalibase.errors;

/**
 * What a dialect puts in an answer in place of an array it could not build. MySQL's JSON_ARRAYAGG gives NULL, with a
 * warning only, when its result passes max_allowed_packet, which reads exactly like "no rows". The dialect asks for this
 * key instead, so the engine can tell the two apart and refuse the request instead of answering empty.
 */
public final class ResultTooLarge {

    /** A key no table is likely to name a column, and plain text, so it reads the same in SQL and in the JSON that comes back. */
    public static final String MARKER = "~excalibase_result_too_large~";

    private ResultTooLarge() {
    }

    /**
     * Throws the refusal when a read's answer is missing. A read always has an answer, an empty array or object at
     * least, so none means the database could not build it: MySQL gives NULL, with a warning only, for a JSON value over
     * max_allowed_packet wherever it is built (an enclosing object as well as the array).
     */
    public static void checkRead(String json) {
        if (json == null) {
            throw new DataErrorException(DataError.resultTooLarge());
        }
        check(json);
    }

    /** Throws the refusal when {@code json}, an answer the database built, holds the marker. */
    public static void check(String json) {
        if (json != null && json.contains(MARKER)) {
            throw new DataErrorException(DataError.resultTooLarge());
        }
    }
}
