package io.github.excalibase.document;

import io.github.excalibase.rls.UserContext;
import io.github.excalibase.security.Principal;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Who is asking, as the document engine's rules see it: the one role the request runs as, the user id and every
 * session variable ({@code x-excalibase-*}) the SQL permission path binds. A variable the request lacks resolves to
 * nothing, which the rule model refuses rather than defaulting.
 */
final class RequestCaller implements UserContext {

    private static final String USER_ID = Principal.SESSION_VARIABLE_PREFIX + "user-id";

    private final String role;
    private final Map<String, String> variables;

    RequestCaller(Principal principal, String projectId) {
        this.role = principal.role();
        this.variables = principal.sessionVariables(projectId);
    }

    @Override
    public String userId() {
        return variables.get(USER_ID);
    }

    @Override
    public String tenantId() {
        return null;
    }

    @Override
    public Set<String> roles() {
        return Set.of(role);
    }

    @Override
    public Set<String> groupIds() {
        return Set.of();
    }

    @Override
    public Object resolveVariable(String name) {
        return name == null ? null : variables.get(name.toLowerCase(Locale.ROOT));
    }
}
