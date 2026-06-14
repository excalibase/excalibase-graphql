package io.github.excalibase.config;

import io.github.excalibase.security.JwtAuthFilter;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    private final boolean jwtEnabled;
    private final String provisioningUrl;

    public SecurityConfig(
            @Value("${app.security.jwt-enabled:false}") boolean jwtEnabled,
            @Value("${app.security.multi-tenant.provisioning-url:}") String provisioningUrl) {
        this.jwtEnabled = jwtEnabled;
        this.provisioningUrl = provisioningUrl;
    }

    /**
     * Fail fast at startup when the app is configured for multi-tenant / provisioning
     * but JWT auth is disabled. A multi-tenant deployment must never run
     * unauthenticated — without JWT there is no tenant claim to route or isolate on.
     */
    @PostConstruct
    void validateMultiTenantRequiresJwt() {
        boolean multiTenant = provisioningUrl != null && !provisioningUrl.isBlank();
        if (multiTenant && !jwtEnabled) {
            throw new IllegalStateException(
                    "app.security.multi-tenant.provisioning-url is set but app.security.jwt-enabled=false — "
                            + "a multi-tenant deployment must run with JWT authentication enabled. "
                            + "Set app.security.jwt-enabled=true.");
        }
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           @Autowired(required = false) JwtAuthFilter jwtAuthFilter) throws Exception {
        // CSRF disabled — stateless JWT API, no session cookies, Bearer-token auth only
        http.csrf(csrf -> csrf.disable()); // NOSONAR

        if (jwtEnabled) {
            // Fail-closed: with JWT enabled, every request must carry a verified token.
            // JwtAuthFilter authenticates the request; requests with no/invalid token
            // are rejected here (401) instead of falling through to permitAll.
            //
            // WebSocket upgrade endpoints are exempt from this HTTP rule because their
            // auth is enforced in their own layer: JwtHandshakeInterceptor (header path)
            // and the handlers' connection_init + subscribe guards (browser path, which
            // cannot set an Authorization header on the upgrade). Actuator health/info
            // probes stay open for liveness/readiness.
            http.authorizeHttpRequests(auth -> auth
                    // WS upgrades are HTTP GET (browsers can't set Authorization on the
                    // upgrade); the GraphQL/REST data APIs are POST and must be authenticated.
                    .requestMatchers(HttpMethod.GET, "/graphql", "/api/v1/realtime").permitAll()
                    .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                    .anyRequest().authenticated());
            // Stateless Bearer-token API: a missing/anonymous credential is 401
            // (Unauthorized), not the framework default of 403, so clients know to
            // present a token.
            http.exceptionHandling(ex -> ex.authenticationEntryPoint(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        } else {
            // Single-tenant / standalone: permissive by design.
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        }

        if (jwtAuthFilter != null) {
            http.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        }

        return http.build();
    }
}
