package io.github.excalibase.config;

import io.github.excalibase.security.TokenFileSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The single service-token source for every outbound call to the control plane —
 * RLS/CLS policies, exposure grants, project CORS origins and tenant vault
 * credentials. One bean rather than one property per caller: the platform
 * delivers the token as a rotated file, so a caller wired to its own property
 * silently keeps sending a stale or empty bearer once rotation happens.
 *
 * <p>Unconditional, because the CORS source is built whether or not JWT auth and
 * multi-tenancy are enabled.
 */
@Configuration
public class ProvisioningTokenConfig {

    @Bean
    public TokenFileSource provisioningTokenSource(
            @Value("${app.security.rls.policy-pat-file:${app.security.multi-tenant.provisioning-pat-file:}}") String tokenFilePath,
            @Value("${app.security.rls.policy-pat:${app.security.multi-tenant.provisioning-pat:}}") String literalToken) {
        return new TokenFileSource(tokenFilePath, literalToken);
    }
}
