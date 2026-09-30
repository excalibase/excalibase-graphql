package io.github.excalibase.permissions;

import java.util.Objects;

/**
 * A permission expression that cannot be applied to this request or this schema. The request fails
 * with {@link #code()}; the expression is never treated as true, never as false and never guessed.
 */
public class PermissionEvaluationException extends RuntimeException {

    public static final String MISSING_SESSION_VARIABLE = "missing_session_variable";
    public static final String INVALID_SESSION_VARIABLE = "invalid_session_variable";
    public static final String INVALID_LITERAL = "invalid_literal";
    public static final String UNKNOWN_TABLE = "unknown_table";
    public static final String UNKNOWN_COLUMN = "unknown_column";
    public static final String UNKNOWN_RELATIONSHIP = "unknown_relationship";
    public static final String UNSUPPORTED_OPERATOR = "unsupported_operator";
    public static final String UNSUPPORTED_COLUMN_TYPE = "unsupported_column_type";
    public static final String INVALID_LIKE_PATTERN = "invalid_like_pattern";

    private final String code;

    public PermissionEvaluationException(String code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public String code() {
        return code;
    }
}
