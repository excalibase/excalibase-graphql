package io.github.excalibase.permissions;

import java.util.Objects;
import java.util.Optional;

/** One role's permissions on one schema-qualified table; an empty operation does not exist for the role. */
public record TablePermissions(String table,
                               String role,
                               Optional<SelectPermission> select,
                               Optional<InsertPermission> insert,
                               Optional<UpdatePermission> update,
                               Optional<DeletePermission> delete) {

    public TablePermissions {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(select, "select");
        Objects.requireNonNull(insert, "insert");
        Objects.requireNonNull(update, "update");
        Objects.requireNonNull(delete, "delete");
    }
}
