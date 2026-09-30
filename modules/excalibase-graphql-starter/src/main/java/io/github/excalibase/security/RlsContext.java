package io.github.excalibase.security;

import java.util.Map;

/**
 * Per-request holder for the request's permission guards: the row filter every read and write
 * ANDs in, and the write guard (presets and checks). Populated once the project and the role are
 * known (by {@code JwtAuthFilter}), read by the compilers, and cleared in a {@code finally} so the
 * ThreadLocal never leaks across pooled request threads.
 *
 * <p>Lives in the starter module so the generic compiler can consult it without depending on the api
 * module. {@code null} means no guards: a deployment without permissions, or the {@code service} role.
 */
public final class RlsContext {

    private static final ThreadLocal<RlsWhereContributor> CONTRIBUTOR = new ThreadLocal<>();
    private static final ThreadLocal<WriteGuard> WRITE_GUARD = new ThreadLocal<>();
    private static final ThreadLocal<Map<String, String>> SESSION_VARIABLES = new ThreadLocal<>();

    private RlsContext() {}

    public static RlsWhereContributor current() {
        return CONTRIBUTOR.get();
    }

    public static void set(RlsWhereContributor contributor) {
        CONTRIBUTOR.set(contributor);
    }

    public static WriteGuard writeGuard() {
        return WRITE_GUARD.get();
    }

    public static void setWriteGuard(WriteGuard guard) {
        WRITE_GUARD.set(guard);
    }

    /**
     * The request's session variables (spec §2), for a tracked function's session argument. Set for
     * every role, {@code service} included; empty when the request has none.
     */
    public static Map<String, String> sessionVariables() {
        Map<String, String> variables = SESSION_VARIABLES.get();
        return variables == null ? Map.of() : variables;
    }

    public static void setSessionVariables(Map<String, String> variables) {
        SESSION_VARIABLES.set(Map.copyOf(variables));
    }

    public static void clear() {
        CONTRIBUTOR.remove();
        WRITE_GUARD.remove();
        SESSION_VARIABLES.remove();
    }
}
