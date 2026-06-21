package io.github.excalibase.config;

import io.github.excalibase.config.datasource.DynamicDataSourceManager;
import io.github.excalibase.rls.InMemoryPolicyProvider;
import io.github.excalibase.rls.PolicyProvider;
import io.github.excalibase.rls.ProvisioningPolicyProvider;
import io.github.excalibase.rls.RlsPolicyEnforcer;
import io.github.excalibase.rls.jdbc.QuoteStyle;
import io.github.excalibase.security.JwtAuthFilter;
import io.github.excalibase.security.JwtService;
import io.github.excalibase.security.PostgresRoleResolver;
import io.github.excalibase.service.VaultCredentialService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "app.security.jwt-enabled", havingValue = "true")
@EnableConfigurationProperties(SecurityProperties.class)
public class JwtSecurityConfig {

    @Bean
    public JwtService jwtService(
            SecurityProperties security,
            @Value("${app.cache.schema-ttl-minutes:30}") int ttlMinutes) {

        SecurityProperties.Auth auth = security.auth();
        if (auth == null) {
            throw new IllegalStateException(
                    "jwt-enabled=true requires app.security.auth.jwks-url or app.security.auth.hmac-secret");
        }

        boolean hasJwks = auth.hasJwksUrl();
        boolean hasHmac = auth.hasHmacSecret();

        if (hasJwks && hasHmac) {
            throw new IllegalStateException(
                    "app.security.auth: set either jwks-url or hmac-secret, not both");
        }
        if (!hasJwks && !hasHmac) {
            throw new IllegalStateException(
                    "jwt-enabled=true requires app.security.auth.jwks-url or app.security.auth.hmac-secret");
        }

        if (hasJwks) {
            return new JwtService(auth.jwksUrl(), ttlMinutes);
        }

        return new JwtService(auth.hmacSecret());
    }

    /**
     * Remote policy source: fetches a project's RLS/CLS policies from the
     * provisioning service over HTTP and caches them per project. Enabled by
     * setting {@code app.security.rls.policy-url} (the provisioning API root).
     * When unset, the in-memory fallback below is used instead, so single-tenant
     * / standalone deployments and tests are unaffected.
     */
    @Bean
    @ConditionalOnProperty(name = "app.security.rls.policy-url")
    public PolicyProvider provisioningPolicyProvider(
            @Value("${app.security.rls.policy-url}") String policyUrl,
            @Value("${app.security.rls.policy-pat:${app.security.multi-tenant.provisioning-pat:}}") String policyPat,
            @Value("${app.security.rls.policy-ttl-ms:30000}") long policyTtlMs) {
        return new ProvisioningPolicyProvider(policyUrl, policyPat, policyTtlMs);
    }

    /**
     * Default in-process policy source. Empty until policies are pushed in, so
     * the RLS path is a no-op passthrough out of the box. {@link
     * ConditionalOnMissingBean} lets the provisioning-backed provider above
     * replace it when {@code app.security.rls.policy-url} is configured.
     */
    @Bean
    @ConditionalOnMissingBean(PolicyProvider.class)
    public PolicyProvider policyProvider() {
        return new InMemoryPolicyProvider();
    }

    @Bean
    public RlsPolicyEnforcer rlsPolicyEnforcer(PolicyProvider policyProvider,
            @Value("${app.database-type:postgres}") String databaseType) {
        // MySQL/MariaDB quote identifiers with backticks; everything else uses
        // ANSI double quotes. Mis-quoting silently mis-filters, so it's keyed
        // off the configured database type rather than guessed at query time.
        QuoteStyle quoteStyle = "mysql".equalsIgnoreCase(databaseType)
                ? QuoteStyle.BACKTICK
                : QuoteStyle.ANSI;
        return new RlsPolicyEnforcer(policyProvider, quoteStyle);
    }

    @Bean
    public JwtAuthFilter jwtAuthFilter(JwtService jwtService, PostgresRoleResolver roleResolver,
                                       RlsPolicyEnforcer rlsPolicyEnforcer) {
        return new JwtAuthFilter(jwtService, roleResolver, rlsPolicyEnforcer);
    }

    // Multi-tenant beans — only when provisioning-url is configured
    @Bean
    @ConditionalOnProperty(name = "app.security.multi-tenant.provisioning-url")
    public VaultCredentialService vaultCredentialService(SecurityProperties security) {
        SecurityProperties.MultiTenant mt = security.multiTenant();
        return new VaultCredentialService(mt.provisioningUrl(), mt.provisioningPat());
    }

    @Bean
    @ConditionalOnProperty(name = "app.security.multi-tenant.provisioning-url")
    public DynamicDataSourceManager dynamicDataSourceManager(
            VaultCredentialService vaultCredentialService,
            @Value("${app.cache.schema-ttl-minutes:30}") int ttlMinutes,
            @Value("${app.hikari.tenant-pool-size:5}") int poolSize) {
        return new DynamicDataSourceManager(vaultCredentialService, ttlMinutes, poolSize);
    }


}
