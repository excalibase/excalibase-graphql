package io.github.excalibase.schema;

import io.github.excalibase.security.Principal;

/**
 * Reads one caller's exposure without going through a request's schema. Realtime
 * needs this because a CDC event is not a field anybody asked for, so there is no
 * served schema on its path out.
 */
public interface ExposureSource {

    TableExposure exposureFor(String orgSlug, String projectId, Principal principal);

    /** True when permissions apply here, so a session that resolved no principal must be refused. */
    default boolean requiresPrincipal() {
        return true;
    }
}
