package io.github.excalibase.rls;

import java.util.List;

/**
 * A project's exposure configuration as the control plane serves it.
 *
 * <p>{@code enforced} is an explicit field and is never inferred from the grant
 * list: {@code enforced=true} with zero grants means "expose nothing", while
 * {@code enforced=false} means the project has not opted in and its schema is
 * served whole. Collapsing those two cases is the difference between a project
 * that is locked down and one that is wide open.
 */
public record TableGrants(String projectId, boolean enforced, List<TableGrant> grants) {

    public TableGrants {
        grants = (grants == null) ? List.of() : List.copyOf(grants);
    }

    /** The configuration for a project that has not opted into exposure filtering. */
    public static TableGrants unenforced(String projectId) {
        return new TableGrants(projectId, false, List.of());
    }

    public List<TableGrant> enabledGrants() {
        return grants.stream().filter(TableGrant::enabled).toList();
    }
}
