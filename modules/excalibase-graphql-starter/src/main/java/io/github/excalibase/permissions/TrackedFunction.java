package io.github.excalibase.permissions;

import java.util.Objects;

/**
 * A database function a project tracks (spec §6).
 *
 * @param sessionArgument the json/jsonb argument that receives the session variables, or null
 */
public record TrackedFunction(String function, ExposedAs exposedAs, boolean inferPermissions,
                              String sessionArgument) {

    /** Decided by the control plane from the function's volatility. */
    public enum ExposedAs { QUERY, MUTATION }

    public TrackedFunction {
        Objects.requireNonNull(function, "function");
        Objects.requireNonNull(exposedAs, "exposedAs");
    }
}
