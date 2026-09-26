package io.github.excalibase.security;

import io.github.excalibase.config.datasource.DynamicDataSourceManager;

/**
 * Which projects this deployment serves. A path project that is not one of them
 * is refused before anything is read on its behalf; there is no default project
 * a request falls back to.
 */
@FunctionalInterface
public interface KnownProjects {

    /** Returns normally for a known project; throws {@link UnknownProjectException} otherwise. */
    void require(String projectId);

    /** Single-database mode: the one configured project and nothing else. */
    static KnownProjects pinned(String projectId) {
        return requested -> {
            if (!projectId.equals(requested)) {
                throw new UnknownProjectException(requested);
            }
        };
    }

    /**
     * Multi-tenant mode: a project is known when its own database resolves. A vault
     * 404 is remembered in {@code unknown}; any other failure is not.
     */
    static KnownProjects tenants(DynamicDataSourceManager dataSourceManager, UnknownProjectCache unknown) {
        return projectId -> {
            if (unknown.isKnownMissing(projectId)) {
                throw new UnknownProjectException(projectId);
            }
            try {
                dataSourceManager.getDataSource(null, projectId);
            } catch (UnknownProjectException e) {
                unknown.recordMissing(projectId);
                throw e;
            }
        };
    }

    /**
     * Picks the mode from configuration and refuses to guess: single-database mode
     * needs the project id pinned, multi-tenant mode needs tenant routing, and
     * both at once is ambiguous.
     */
    static KnownProjects forDeployment(String pinnedProjectId, String tenantProvisioningUrl,
                                       DynamicDataSourceManager dataSourceManager, UnknownProjectCache unknown) {
        boolean pinned = pinnedProjectId != null && !pinnedProjectId.isBlank();
        boolean multiTenant = tenantProvisioningUrl != null && !tenantProvisioningUrl.isBlank();
        if (multiTenant) {
            if (pinned) {
                throw new IllegalStateException("app.project-id pins a single-database deployment, but "
                        + "app.security.multi-tenant.provisioning-url makes this one multi-tenant: set one, not both");
            }
            if (dataSourceManager == null) {
                throw new IllegalStateException("Multi-tenant mode resolves projects through authenticated "
                        + "provisioning: set app.security.jwt-enabled=true");
            }
            return tenants(dataSourceManager, unknown);
        }
        if (!pinned) {
            throw new IllegalStateException("Single-database mode requires app.project-id (APP_PROJECT_ID): "
                    + "the one project this database serves. Requests naming any other project are refused.");
        }
        return pinned(pinnedProjectId.trim());
    }
}
