package io.github.excalibase.rls;

import java.util.List;

/**
 * Supplies the row-level-security policies in force for a project.
 *
 * <p>Policies are authored + stored in the provisioning service
 * (EXC-318) and pushed to this process; a {@code PolicyProvider} is the
 * read seam the RLS enforcer consults at query time. Implementations
 * must be cheap to call on the hot path (cache, not network) and must
 * never return {@code null}.
 */
public interface PolicyProvider {

    /**
     * Returns the policies scoped to {@code projectId}, or an empty list
     * when the project is unknown / has no policies. Never {@code null}.
     */
    List<Policy> policiesFor(String projectId);
}
