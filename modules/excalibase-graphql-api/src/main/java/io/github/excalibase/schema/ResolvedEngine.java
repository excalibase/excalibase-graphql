package io.github.excalibase.schema;

import io.github.excalibase.security.Principal;
import io.micronaut.core.propagation.PropagatedContextElement;

/**
 * The engine the authentication filter resolved for one request, carried on that request's context so whichever
 * thread runs the handler is served the same one; the permission source is not asked again, and a permission
 * change cannot slip in between. It lives and dies with the request: no thread holds it afterwards.
 */
record ResolvedEngine(Principal principal, String projectId, GraphqlSchemaManager.EngineState state)
        implements PropagatedContextElement {

    boolean serves(Principal caller, String project) {
        return principal == caller && java.util.Objects.equals(projectId, project);
    }
}
