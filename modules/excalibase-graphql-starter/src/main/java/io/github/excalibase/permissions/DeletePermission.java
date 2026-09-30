package io.github.excalibase.permissions;

import java.util.Objects;

public record DeletePermission(BoolExp filter) {

    public DeletePermission {
        Objects.requireNonNull(filter, "filter");
    }
}
