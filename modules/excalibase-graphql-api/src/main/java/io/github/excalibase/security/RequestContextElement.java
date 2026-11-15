package io.github.excalibase.security;

import io.github.excalibase.config.datasource.TenantContext;
import io.micronaut.core.propagation.ThreadPropagatedContextElement;
import org.slf4j.MDC;

import java.util.Map;

/**
 * What the authentication filter learned about a request, carried to whichever thread runs the handler: the
 * tenant (routes the database), the request's permission guards and its session variables. Each thread that
 * runs the handler has them set for the duration and put back afterwards, so nothing leaks across requests.
 */
public record RequestContextElement(String tenantId, String orgSlug, String projectName, String orgName,
                                    RlsWhereContributor guard, WriteGuard writeGuard,
                                    Map<String, String> sessionVariables)
        implements ThreadPropagatedContextElement<RequestContextElement.Saved> {

    /** The values the thread held before. */
    public record Saved(String tenantId, String orgSlug, RlsWhereContributor guard, WriteGuard writeGuard,
                        Map<String, String> sessionVariables, String mdcTenant) {}

    @Override
    public Saved updateThreadContext() {
        Saved before = new Saved(TenantContext.getTenantId(), TenantContext.getOrgSlug(), RlsContext.current(),
                RlsContext.writeGuard(), RlsContext.sessionVariables(), MDC.get("tenant"));
        TenantContext.setTenantId(tenantId);
        TenantContext.setOrgSlug(orgSlug);
        RlsContext.clear();
        if (sessionVariables != null) {
            RlsContext.setSessionVariables(sessionVariables);
        }
        if (guard != null) {
            RlsContext.set(guard);
        }
        if (writeGuard != null) {
            RlsContext.setWriteGuard(writeGuard);
        }
        MDC.put("tenant", tenantId);
        MDC.put("org", orEmpty(orgSlug));
        MDC.put("project_name", orEmpty(projectName));
        MDC.put("org_name", orEmpty(orgName));
        return before;
    }

    @Override
    public void restoreThreadContext(Saved before) {
        TenantContext.clear();
        RlsContext.clear();
        MDC.remove("org");
        MDC.remove("project_name");
        MDC.remove("org_name");
        if (before == null) {
            MDC.remove("tenant");
            return;
        }
        if (before.tenantId() != null) {
            TenantContext.setTenantId(before.tenantId());
            TenantContext.setOrgSlug(before.orgSlug());
            MDC.put("tenant", before.tenantId());
        } else {
            MDC.remove("tenant");
        }
        if (before.guard() != null) {
            RlsContext.set(before.guard());
        }
        if (before.writeGuard() != null) {
            RlsContext.setWriteGuard(before.writeGuard());
        }
        if (before.sessionVariables() != null && !before.sessionVariables().isEmpty()) {
            RlsContext.setSessionVariables(before.sessionVariables());
        }
    }

    private static String orEmpty(String value) {
        return value != null ? value : "";
    }
}
