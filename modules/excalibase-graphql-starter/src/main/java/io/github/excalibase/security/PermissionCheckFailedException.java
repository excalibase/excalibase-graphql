package io.github.excalibase.security;

import java.util.Optional;

/**
 * A written row does not pass its permission's check (docs/features/permissions.md §5). The statement
 * that wrote it raises an error carrying {@link #SQL_MARKER}, so the whole mutation rolls back; the
 * surfaces report {@value #CODE}.
 */
public class PermissionCheckFailedException extends RuntimeException {

    public static final String CODE = "permission_check_failed";

    /** Text the failing statement's error carries; it names nothing about the row. */
    public static final String SQL_MARKER = "excalibase_permission_check_failed";

    public PermissionCheckFailedException() {
        super("A written row does not pass the role's permission check");
    }

    public String code() {
        return CODE;
    }

    /**
     * A scalar SQL expression that is NULL when {@code violation} is false and raises an error carrying
     * {@link #SQL_MARKER} when it is true. The cast is not constant, so it only runs when a row fails.
     */
    public static String raiseWhen(String violation) {
        return "CAST(CASE WHEN " + violation + " THEN '" + SQL_MARKER + "' END AS integer)";
    }

    /** The failure behind a database error, looked for anywhere in the cause chain. */
    public static Optional<PermissionCheckFailedException> find(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof PermissionCheckFailedException failed) {
                return Optional.of(failed);
            }
            if (current.getMessage() != null && current.getMessage().contains(SQL_MARKER)) {
                return Optional.of(new PermissionCheckFailedException());
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return Optional.empty();
    }
}
