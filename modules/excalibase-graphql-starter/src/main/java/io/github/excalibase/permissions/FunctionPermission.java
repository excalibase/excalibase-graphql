package io.github.excalibase.permissions;

import java.util.Objects;

/** An explicit grant for {@code role} to call the tracked {@code function}. */
public record FunctionPermission(String function, String role) {

    public FunctionPermission {
        Objects.requireNonNull(function, "function");
        Objects.requireNonNull(role, "role");
    }
}
