package io.github.excalibase.permissions;

import io.github.excalibase.security.Principal;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One project's whole permission document, as the control plane serves it (spec §8).
 *
 * @param version increases on every permission write, so two documents can be told apart
 */
public record PermissionSet(String projectId,
                            long version,
                            List<TablePermissions> tables,
                            List<TrackedFunction> functions,
                            List<FunctionPermission> functionPermissions) {

    public PermissionSet {
        Objects.requireNonNull(projectId, "projectId");
        tables = List.copyOf(tables);
        functions = List.copyOf(functions);
        functionPermissions = List.copyOf(functionPermissions);
    }

    /**
     * What {@code role} may reach. The {@code service} role is refused: its bypass is decided when the
     * access plan is built, never by reading an (empty) permission set for it.
     */
    public RolePermissions forRole(String role) {
        if (!Principal.isValidRoleName(role) || Principal.SERVICE.equals(role)) {
            throw new IllegalArgumentException("Not a permission-governed role");
        }
        Map<String, TablePermissions> byTable = new LinkedHashMap<>();
        tables.stream()
                .filter(entry -> entry.role().equals(role))
                .forEach(entry -> byTable.put(entry.table(), entry));
        return new RolePermissions(role, byTable, functions, explicitlyPermitted(role));
    }

    /**
     * Functions {@code role} holds an explicit function permission for. Spec §6 inference (a STABLE
     * function callable by every role that can select its return table) needs the return table, which
     * only the reflected schema knows, so it is applied when the access plan is built.
     */
    public Set<String> explicitlyPermitted(String role) {
        Set<String> permitted = new LinkedHashSet<>();
        functionPermissions.stream()
                .filter(grant -> grant.role().equals(role))
                .forEach(grant -> permitted.add(grant.function()));
        return Set.copyOf(permitted);
    }
}
