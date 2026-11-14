package io.github.excalibase.security;

import io.github.excalibase.config.datasource.DynamicDataSourceManager;

import java.util.regex.Pattern;

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

    /** The shape every project id has; anything else cannot exist, so no lookup is made for it. */
    Pattern PROJECT_ID = Pattern.compile("[a-zA-Z0-9_-]{1,64}");

    /** Looks up at most {@link #DEFAULT_LOOKUPS_PER_SECOND} unresolved projects a second. */
    int DEFAULT_LOOKUPS_PER_SECOND = 50;

    static KnownProjects tenants(DynamicDataSourceManager dataSourceManager, UnknownProjectCache unknown) {
        return tenants(dataSourceManager, unknown, LookupLimiter.perSecond(DEFAULT_LOOKUPS_PER_SECOND));
    }

    /**
     * Multi-tenant mode: a project is known when its own database resolves. An id that cannot be a
     * project is unknown without a lookup; a vault 404 is remembered in {@code unknown}, any other
     * failure is not; a project not yet resolved is looked up only within {@code lookups}' rate.
     */
    static KnownProjects tenants(DynamicDataSourceManager dataSourceManager, UnknownProjectCache unknown,
                                 LookupLimiter lookups) {
        return projectId -> {
            if (!PROJECT_ID.matcher(projectId).matches() || unknown.isKnownMissing(projectId)) {
                throw new UnknownProjectException(projectId);
            }
            if (!dataSourceManager.isCached(projectId) && !lookups.tryAcquire()) {
                throw new ProjectLookupThrottledException();
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
        return forDeployment(pinnedProjectId, tenantProvisioningUrl, dataSourceManager, unknown,
                LookupLimiter.perSecond(DEFAULT_LOOKUPS_PER_SECOND));
    }

    static KnownProjects forDeployment(String pinnedProjectId, String tenantProvisioningUrl,
                                       DynamicDataSourceManager dataSourceManager, UnknownProjectCache unknown,
                                       LookupLimiter lookups) {
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
            return tenants(dataSourceManager, unknown, lookups);
        }
        if (!pinned) {
            throw new IllegalStateException("Single-database mode requires app.project-id (APP_PROJECT_ID): "
                    + "the one project this database serves. Requests naming any other project are refused.");
        }
        return pinned(pinnedProjectId.trim());
    }
}
