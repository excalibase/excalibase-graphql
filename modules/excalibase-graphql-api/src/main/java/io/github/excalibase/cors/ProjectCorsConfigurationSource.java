package io.github.excalibase.cors;

import io.github.excalibase.security.ProjectPath;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.List;
import java.util.Objects;

/**
 * Resolves CORS per request by project. The project is the leading path
 * segment (the same rule RLS uses); its origins come from the
 * {@link ProjectCorsProvider}, which caches them per project. Every
 * project-scoped surface — GraphQL, REST and the WebSocket upgrades on those
 * paths — goes through here because it runs as a servlet filter.
 *
 * <p>Fail-closed rules:
 * <ul>
 *   <li>project route, origins known → only those origins (or {@code *}) are allowed;</li>
 *   <li>project route, no origins → nothing is allowed: no CORS headers, preflight refused;</li>
 *   <li>project route, provider failure with nothing cached (unknown project,
 *       provisioning down) → nothing is allowed; the platform default is never used;</li>
 *   <li>non-project route (health, actuator, legacy unscoped) → the platform default;</li>
 *   <li>no provider configured (standalone, no provisioning) → the platform default everywhere.</li>
 * </ul>
 * Non-browser clients send no {@code Origin} and are unaffected by any of this.
 */
public final class ProjectCorsConfigurationSource implements CorsConfigurationSource {

    private static final Logger log = LoggerFactory.getLogger(ProjectCorsConfigurationSource.class);

    private static final List<String> ALLOWED_METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    private static final List<String> EXPOSED_HEADERS = List.of("Content-Range", "Location");
    private static final long MAX_AGE_SECONDS = 3600L;

    private final ProjectCorsProvider provider;
    private final CorsConfiguration platformDefault;

    /**
     * @param provider        per-project allowlist source; {@code null} when no
     *                        provisioning is configured (platform default everywhere)
     * @param platformDefault configuration for routes that carry no project
     */
    public ProjectCorsConfigurationSource(ProjectCorsProvider provider, CorsConfiguration platformDefault) {
        this.provider = provider;
        this.platformDefault = Objects.requireNonNull(platformDefault, "platformDefault");
    }

    @Override
    public CorsConfiguration getCorsConfiguration(HttpServletRequest request) {
        String projectId = ProjectPath.fromUri(request.getRequestURI());
        if (projectId == null || provider == null) {
            return platformDefault;
        }
        try {
            return configFor(provider.originsFor(projectId));
        } catch (CorsOriginsFetchException e) {
            // The project id is request-derived, so it stays out of the log line;
            // the 403 in the access log carries the path.
            log.warn("CORS origins unavailable for a project route, denying cross-origin access ({})",
                    e.getCause() == null ? "provisioning error" : e.getCause().getClass().getSimpleName());
            return configFor(List.of());
        }
    }

    /**
     * The browser-facing API's shared CORS settings for a given origin list.
     * Auth is a Bearer token, never a cookie, so credentials stay off — which
     * is also what makes the {@code *} entry safe to honour.
     */
    public static CorsConfiguration configFor(List<String> origins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.copyOf(origins));
        config.setAllowedMethods(ALLOWED_METHODS);
        config.setAllowedHeaders(List.of(CorsConfiguration.ALL));
        config.setExposedHeaders(EXPOSED_HEADERS);
        config.setAllowCredentials(false);
        config.setMaxAge(MAX_AGE_SECONDS);
        return config;
    }
}
