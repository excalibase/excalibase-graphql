package io.github.excalibase.errors;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A request the database or the engine refused for a reason the client can act on: a status, a stable
 * code, a message that names no SQL, file or line, and the constraint or column it is about when known.
 * The REST and GraphQL surfaces render it with {@link #restBody()} and {@link #graphqlError()}.
 */
public record DataError(int status, String code, String message, String constraint, String column) {

    public static final String UNIQUE_VIOLATION = "unique_violation";
    public static final String FOREIGN_KEY_VIOLATION = "foreign_key_violation";
    public static final String CHECK_VIOLATION = "check_violation";
    public static final String NOT_NULL_VIOLATION = "not_null_violation";
    public static final String EXCLUSION_VIOLATION = "exclusion_violation";
    /** Any other integrity failure the database does not classify further. */
    public static final String CONSTRAINT_VIOLATION = "constraint_violation";
    /** An error a function or trigger raised itself (Postgres RAISE, MySQL SIGNAL). */
    public static final String RAISED_EXCEPTION = "raised_exception";
    public static final String INVALID_VALUE = "invalid_value";
    public static final String PERMISSION_DENIED = "permission_denied";
    /** An embed names a table that more than one relationship leads to; the request must say which. */
    public static final String AMBIGUOUS_RELATIONSHIP = "ambiguous_relationship";
    /** The project's database could not be reached: down, restarting, or shut down by a pause. */
    public static final String PROJECT_UNAVAILABLE = "project_unavailable";
    public static final String UNKNOWN_RELATIONSHIP_HINT = "unknown_relationship_hint";

    private static final int BAD_REQUEST = 400;
    private static final int FORBIDDEN = 403;
    private static final int MULTIPLE_CHOICES = 300;
    private static final int CONFLICT = 409;
    private static final int SERVICE_UNAVAILABLE = 503;
    private static final String KEY_CODE = "code";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_CONSTRAINT = "constraint";
    private static final String KEY_COLUMN = "column";

    public DataError {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }

    public static DataError conflict(String code, String message, String constraint) {
        return new DataError(CONFLICT, code, message, constraint, null);
    }

    public static DataError badRequest(String code, String message) {
        return new DataError(BAD_REQUEST, code, message, null, null);
    }

    public static DataError badRequest(String code, String message, String constraint, String column) {
        return new DataError(BAD_REQUEST, code, message, constraint, column);
    }

    /** Several relationships fit {@code relation}; {@code options} are embed names that each pick exactly one. */
    public static DataError ambiguousRelationship(String relation, java.util.List<String> options) {
        return new DataError(MULTIPLE_CHOICES, AMBIGUOUS_RELATIONSHIP, "More than one relationship fits '"
                + relation + "'; use one of: " + String.join(", ", options), null, null);
    }

    /** An embed's {@code !hint} names no foreign key column of the relationship it is on. */
    public static DataError unknownRelationshipHint(String relation, String hint) {
        return badRequest(UNKNOWN_RELATIONSHIP_HINT,
                "No foreign key column '" + hint + "' leads to '" + relation + "'");
    }

    /** The database is not there; says nothing of where it is or what the driver reported. */
    public static DataError projectUnavailable() {
        return new DataError(SERVICE_UNAVAILABLE, PROJECT_UNAVAILABLE, "Project unavailable", null, null);
    }

    public static DataError forbidden(String message, String column) {
        return new DataError(FORBIDDEN, PERMISSION_DENIED, message, null, column);
    }

    public static DataError invalidValue(String column, String message) {
        return new DataError(BAD_REQUEST, INVALID_VALUE, message, null, column);
    }

    /** This error attributed to {@code name}; an error that already names a column keeps it. */
    public DataError withColumn(String name) {
        if (column != null || name == null) {
            return this;
        }
        return new DataError(status, code, "Invalid value for column '" + name + "': " + message, constraint, name);
    }

    /** REST body: {@code code}, {@code message}, the {@code error} key every REST error carries, and what it names. */
    public Map<String, Object> restBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(KEY_CODE, code);
        body.put(KEY_MESSAGE, message);
        body.put("error", message);
        putNamed(body);
        return Collections.unmodifiableMap(body);
    }

    /** GraphQL {@code errors[]} entry: the message, and the code with what it names in {@code extensions}. */
    public Map<String, Object> graphqlError() {
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put(KEY_CODE, code);
        putNamed(extensions);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put(KEY_MESSAGE, message);
        error.put("extensions", Collections.unmodifiableMap(extensions));
        return Collections.unmodifiableMap(error);
    }

    private void putNamed(Map<String, Object> target) {
        if (constraint != null) {
            target.put(KEY_CONSTRAINT, constraint);
        }
        if (column != null) {
            target.put(KEY_COLUMN, column);
        }
    }
}
