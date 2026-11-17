package io.github.excalibase.config;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;

/**
 * Authentication is on by default. Turning it off ({@code app.security.jwt-enabled=false})
 * removes the JWT verifier, the RLS enforcer and the auth filter, so every request
 * sees every row; that is allowed only with the explicit
 * {@code app.security.insecure-dev-mode=true}, and then logged at ERROR. Without
 * the flag the process refuses to start.
 */
@Context
@Requires(property = "app.security.jwt-enabled", pattern = JwtEnabledSetting.OFF_PATTERN)
public class InsecureDevModeGuard {

    private static final Logger log = LoggerFactory.getLogger(InsecureDevModeGuard.class);

    private final boolean insecureDevMode;
    private final String policyUrl;
    private final String metadataSource;

    public InsecureDevModeGuard(boolean insecureDevMode, String policyUrl) {
        this(insecureDevMode, policyUrl, "");
    }

    @Inject
    public InsecureDevModeGuard(@Value("${app.security.insecure-dev-mode:false}") boolean insecureDevMode,
                                @Value("${app.security.rls.policy-url:}") String policyUrl,
                                @Value("${metadata.source:}") String metadataSource) {
        this.insecureDevMode = insecureDevMode;
        this.policyUrl = policyUrl;
        this.metadataSource = metadataSource;
    }

    @PostConstruct
    void check() {
        if (!insecureDevMode) {
            throw new IllegalStateException("app.security.jwt-enabled=false turns off authentication and "
                    + "Row-Level Security. Configure app.security.auth.jwks-url or app.security.auth.hmac-secret "
                    + "instead, or, for local development only, also set app.security.insecure-dev-mode=true.");
        }
        if (metadataSource != null && !metadataSource.isBlank()) {
            log.warn("SECURITY: metadata.source={} is set but authentication is off, so its permissions are NOT "
                    + "enforced: nothing reads them.", metadataSource);
        }
        boolean policiesConfigured = policyUrl != null && !policyUrl.isBlank();
        log.error("SECURITY: INSECURE DEV MODE. Authentication is off: every request is served without a "
                + "user, without tenant isolation and without Row-Level Security. {}Never run this way "
                + "outside local development.",
                policiesConfigured
                        ? "Row-Level Security is NOT enforced for the policies at " + policyUrl + ". "
                        : "");
    }
}
