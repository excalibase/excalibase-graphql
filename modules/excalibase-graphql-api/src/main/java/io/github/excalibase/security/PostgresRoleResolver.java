package io.github.excalibase.security;

import io.github.excalibase.config.SecurityProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Resolves the Postgres role that should run a given request, based on the verified
 * JWT claims. Maps JWT scope to a Postgres role:
 *
 * <ul>
 *   <li>{@code scope == "public"} (publishable-key exchange) → {@code anon-role}</li>
 *   <li>{@code scope == "service"} (secret-key exchange) → {@code service-role}</li>
 *   <li>{@code scope == "authenticated"} or {@code null} (legacy password tokens):
 *     <ul>
 *       <li>{@code role == "user"} (or absent) → {@code authenticated-default-role}</li>
 *       <li>{@code role} ∈ {@code allowed-roles} → that role</li>
 *       <li>otherwise → {@link RoleNotAllowedException} (HTTP 403)</li>
 *     </ul>
 *   </li>
 *   <li>{@code claims == null} (no JWT — single-tenant deploy fallback) → {@code anon-role}</li>
 *   <li>any other scope value → {@link RoleNotAllowedException} (fail-closed)</li>
 * </ul>
 *
 * <p>The resolved role string is validated against {@link #SAFE_ROLE_NAME} before
 * being interpolated into a {@code SET LOCAL ROLE "<role>"} statement at the call
 * site. {@code SET ROLE} cannot use parameter placeholders, so app-side allowlisting
 * is the only way to defend against SQL injection — a malformed identifier throws.
 *
 * <p>When role switching is disabled (no {@code anon-role} configured),
 * {@link #resolve(JwtClaims)} returns {@code null} and the call site skips the
 * role-switch step. Disabled mode is the zero-overhead default.
 */
@Component
public class PostgresRoleResolver {

    /**
     * Postgres identifier shape: starts with a letter or underscore, followed by up
     * to 62 alphanumeric or underscore characters (matches Postgres NAMEDATALEN=64).
     */
    public static final Pattern SAFE_ROLE_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,62}$");

    private static final String SCOPE_PUBLIC = "public";
    private static final String SCOPE_SERVICE = "service";
    private static final String SCOPE_AUTHENTICATED = "authenticated";
    private static final String DEFAULT_ROLE_CLAIM = "user";

    private final SecurityProperties.Postgres.RoleSwitching config;

    /**
     * Spring constructor — uses {@link ObjectProvider} because {@link SecurityProperties}
     * is only registered as a bean when {@code app.security.jwt-enabled=true}. In
     * non-JWT deployments the resolver gracefully degrades to "feature disabled".
     */
    @Autowired
    public PostgresRoleResolver(ObjectProvider<SecurityProperties> propertiesProvider) {
        this(propertiesProvider.getIfAvailable());
    }

    /** Test/manual constructor — allows direct injection of properties without Spring. */
    public PostgresRoleResolver(SecurityProperties properties) {
        this.config = (properties != null && properties.postgres() != null)
                ? properties.postgres().roleSwitching()
                : null;
    }

    public boolean isEnabled() {
        return config != null && config.isEnabled();
    }

    /**
     * Resolves the Postgres role for the given claims, or {@code null} if role
     * switching is disabled. Throws {@link RoleNotAllowedException} when the JWT
     * carries a role/scope that violates the configured policy.
     */
    public String resolve(JwtClaims claims) {
        if (!isEnabled()) {
            return null;
        }

        if (claims == null) {
            return validated(config.anonRole());
        }

        String scope = claims.scope();
        if (SCOPE_PUBLIC.equals(scope)) {
            return validated(config.anonRole());
        }
        if (SCOPE_SERVICE.equals(scope)) {
            return validated(requireConfigured("service-role", config.serviceRole()));
        }
        if (SCOPE_AUTHENTICATED.equals(scope) || scope == null) {
            return resolveAuthenticated(claims.role());
        }
        throw new RoleNotAllowedException("Unsupported JWT scope: " + scope);
    }

    private String resolveAuthenticated(String role) {
        if (role == null || role.isBlank() || DEFAULT_ROLE_CLAIM.equals(role)) {
            return validated(requireConfigured("authenticated-default-role",
                    config.authenticatedDefaultRole()));
        }
        List<String> allowed = config.allowedRoles();
        if (allowed != null && allowed.contains(role)) {
            return validated(role);
        }
        throw new RoleNotAllowedException("Role not in allowlist: " + role);
    }

    private static String requireConfigured(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "app.security.postgres.role-switching." + name + " is required when role switching is enabled");
        }
        return value;
    }

    private static String validated(String role) {
        if (!SAFE_ROLE_NAME.matcher(role).matches()) {
            throw new RoleNotAllowedException("Role identifier failed safety check: " + role);
        }
        return role;
    }
}
