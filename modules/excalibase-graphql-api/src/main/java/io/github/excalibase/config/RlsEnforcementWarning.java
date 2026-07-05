package io.github.excalibase.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Startup guard for a silent-but-dangerous misconfiguration: when
 * {@code app.security.jwt-enabled=false}, the whole {@link JwtSecurityConfig}
 * (JWT verification, the RLS policy provider, enforcer and auth filter) is not
 * created — there is no user or project context, so Row-Level Security is not
 * enforced at all and every request sees every row.
 *
 * <p>This is easy to miss because nothing fails; the app just serves unfiltered
 * data. This bean is active precisely when JWT is off and logs a loud warning at
 * startup — escalated to an error-level message when RLS policies are actually
 * configured ({@code app.security.rls.policy-url}), which is the unambiguous
 * "policies exist but enforcement is disabled" case.
 */
@Configuration
@ConditionalOnProperty(name = "app.security.jwt-enabled", havingValue = "false", matchIfMissing = true)
public class RlsEnforcementWarning {

    private static final Logger log = LoggerFactory.getLogger(RlsEnforcementWarning.class);

    private final String policyUrl;

    public RlsEnforcementWarning(@Value("${app.security.rls.policy-url:}") String policyUrl) {
        this.policyUrl = policyUrl;
    }

    @PostConstruct
    void warnRlsDisabled() {
        boolean policiesConfigured = policyUrl != null && !policyUrl.isBlank();
        if (policiesConfigured) {
            log.error("SECURITY: app.security.jwt-enabled=false but app.security.rls.policy-url is set ({}). "
                    + "Row-Level Security is NOT enforced — with no JWT there is no user/project context, so the "
                    + "RLS enforcer and auth filter are never created and every request returns all rows. "
                    + "Set app.security.jwt-enabled=true to enforce the configured policies.", policyUrl);
        } else {
            log.warn("app.security.jwt-enabled=false: authentication and Row-Level Security are disabled. All "
                    + "data is served without RLS or tenant isolation. Enable JWT (app.security.jwt-enabled=true) "
                    + "before relying on RLS policies in production.");
        }
    }
}
