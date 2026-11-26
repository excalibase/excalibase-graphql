package io.github.excalibase.compiler;

import io.github.excalibase.security.Principal;

/**
 * A field the caller's schema does not have: no permission rule gives the role the table, or it is not there.
 * The message stays the compiler's own; {@link #messageFor} adds who asked, and the surfaces report {@value #CODE}.
 */
public class UnknownFieldException extends IllegalArgumentException {

    public static final String CODE = "unknown_field";

    private final String fields;

    public UnknownFieldException(String fields) {
        super("Unknown field(s): " + fields);
        this.fields = fields;
    }

    /** The message with the role the request ran as; a request without a token runs as anon. */
    public String messageFor(Principal principal) {
        if (principal != null && principal.bypass()) {
            return getMessage();
        }
        String role = principal == null ? Principal.ANON : principal.role();
        String who = principal == null ? "A request with no token runs as role 'anon'" : "The request runs as role '" + role + "'";
        return getMessage() + ". " + who + ", which has no permission rule for " + fields
                + "; check the token's role and the permissions document, or the name";
    }
}
