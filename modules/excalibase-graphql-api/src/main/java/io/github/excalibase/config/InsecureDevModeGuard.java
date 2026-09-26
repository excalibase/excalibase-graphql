package io.github.excalibase.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Authentication is on by default. Turning it off ({@code app.security.jwt-enabled=false})
 * removes the JWT verifier, the RLS enforcer and the auth filter, so every request
 * sees every row; that is allowed only with the explicit
 * {@code app.security.insecure-dev-mode=true}, and then logged at ERROR. Without
 * the flag the process refuses to start.
 */
@Configuration
@ConditionalOnProperty(name = "app.security.jwt-enabled", havingValue = "false")
public class InsecureDevModeGuard {

    private static final Logger log = LoggerFactory.getLogger(InsecureDevModeGuard.class);

    private final boolean insecureDevMode;
    private final String policyUrl;

    public InsecureDevModeGuard(@Value("${app.security.insecure-dev-mode:false}") boolean insecureDevMode,
                                @Value("${app.security.rls.policy-url:}") String policyUrl) {
        this.insecureDevMode = insecureDevMode;
        this.policyUrl = policyUrl;
    }

    @PostConstruct
    void check() {
        if (!insecureDevMode) {
            throw new IllegalStateException("app.security.jwt-enabled=false turns off authentication and "
                    + "Row-Level Security. Configure app.security.auth.jwks-url or app.security.auth.hmac-secret "
                    + "instead, or, for local development only, also set app.security.insecure-dev-mode=true.");
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
