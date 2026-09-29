package io.github.excalibase.permissions;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One role's slice of a {@link PermissionSet}.
 *
 * @param tables                        schema-qualified table name to the role's permissions on it
 * @param trackedFunctions              every tracked function of the project
 * @param explicitlyPermittedFunctions  functions the role holds an explicit permission for; inference
 *                                      is applied later (see {@link PermissionSet#explicitlyPermitted})
 */
public record RolePermissions(String role,
                              Map<String, TablePermissions> tables,
                              List<TrackedFunction> trackedFunctions,
                              Set<String> explicitlyPermittedFunctions) {

    public RolePermissions {
        Objects.requireNonNull(role, "role");
        tables = Map.copyOf(tables);
        trackedFunctions = List.copyOf(trackedFunctions);
        explicitlyPermittedFunctions = Set.copyOf(explicitlyPermittedFunctions);
    }

    public Optional<TablePermissions> table(String table) {
        return Optional.ofNullable(tables.get(table));
    }

    public Optional<TrackedFunction> trackedFunction(String function) {
        return trackedFunctions.stream().filter(tracked -> tracked.function().equals(function)).findFirst();
    }
}
