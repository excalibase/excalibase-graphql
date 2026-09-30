package io.github.excalibase.schema;

import io.github.excalibase.access.AccessPlan;
import io.github.excalibase.access.ChangeFilter;
import io.github.excalibase.access.ProbeRunner;
import io.github.excalibase.security.Principal;

import java.util.Optional;

/**
 * Where the request filter and the realtime handlers get a role's access plan. They see the plan and
 * what comes from it, never the permission source.
 */
public interface AccessPlans {

    /** The plan of the request's project (from its path) for the role {@code principal} runs as. */
    AccessPlan planFor(Principal principal);

    /** The request that called {@link #planFor} has ended; nothing it resolved may be reused. */
    void requestFinished();

    /**
     * What a realtime subscription on {@code subscriptionKey} ({@code schema_table}, or a bare table in
     * the project's default schema) may deliver, or empty when the role cannot select that table.
     */
    Optional<RealtimeAccess> realtime(String orgSlug, String projectId, Principal principal, String subscriptionKey);

    /** @param table the table key changes are judged against */
    record RealtimeAccess(String table, ChangeFilter filter, ProbeRunner probes) {}
}
