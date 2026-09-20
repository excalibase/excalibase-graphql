package io.github.excalibase.config;

import io.github.excalibase.config.datasource.DynamicDataSourceManager;
import io.github.excalibase.rls.InMemoryPolicyProvider;
import io.github.excalibase.rls.PolicyChangeSubscriber;
import io.github.excalibase.rls.PolicyProvider;
import io.github.excalibase.rls.ProvisioningPolicyProvider;
import io.github.excalibase.rls.RlsPolicyEnforcer;
import io.github.excalibase.schema.GraphqlSchemaManager;
import io.github.excalibase.rls.jdbc.QuoteStyle;
import io.github.excalibase.security.JwtAuthFilter;
import io.github.excalibase.security.JwtService;
import io.github.excalibase.security.TokenFileSource;
import io.github.excalibase.service.VaultCredentialService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

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

        JwtService service = hasJwks
                ? new JwtService(auth.jwksUrl(), ttlMinutes)
                : new JwtService(auth.hmacSecret());

        return service
                .expectedIssuer(auth.issuer())
                .requireAudience(auth.requireAudOrDefault(), auth.audPrefix());
    }

    /**
     * RLS/CLS policy source. When {@code app.security.rls.policy-url} is set,
     * policies are fetched from the provisioning service over HTTP and cached per
     * project with the shared provisioning token source; otherwise an empty
     * in-memory provider is used (RLS is a no-op until
     * policies are pushed in). The choice is made at runtime rather than via
     * {@code @ConditionalOnProperty} so it survives GraalVM AOT, which evaluates
     * build-time conditions when the property may be absent.
     */
    @Bean
    public PolicyProvider policyProvider(
            @Value("${app.security.rls.policy-url:}") String policyUrl,
            TokenFileSource provisioningTokenSource,
            @Value("${app.security.rls.policy-ttl-ms:30000}") long policyTtlMs) {
        if (policyUrl != null && !policyUrl.isBlank()) {
            return new ProvisioningPolicyProvider(policyUrl, provisioningTokenSource, policyTtlMs);
        }
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
    public JwtAuthFilter jwtAuthFilter(JwtService jwtService, RlsPolicyEnforcer rlsPolicyEnforcer) {
        return new JwtAuthFilter(jwtService, rlsPolicyEnforcer);
    }

    /**
     * Invalidates a project's cached policies on a NATS {@code policies.{id}.changed}
     * signal from provisioning, so Studio edits converge immediately rather than
     * waiting out the policy-cache TTL (which stays as the fail-safe). Enablement is
     * read at runtime from {@code app.nats.enabled}; disabled = safe no-op.
     */
    @Bean
    public PolicyChangeSubscriber policyChangeSubscriber(
            PolicyProvider policyProvider,
            GraphqlSchemaManager schemaManager,
            @Value("${app.nats.enabled:false}") boolean natsEnabled,
            @Value("${app.nats.url:nats://localhost:4222}") String natsUrl,
            @Value("${app.nats.username:}") String natsUsername,
            @Value("${app.nats.password:}") String natsPassword,
            @Value("${app.nats.inbox-prefix:}") String natsInboxPrefix) {
        return new PolicyChangeSubscriber(List.of(policyProvider, schemaManager::evict),
                natsEnabled, natsUrl, natsUsername, natsPassword, natsInboxPrefix);
    }

    // Multi-tenant beans — only when provisioning-url is configured
    @Bean
    @ConditionalOnProperty(name = "app.security.multi-tenant.provisioning-url")
    public VaultCredentialService vaultCredentialService(SecurityProperties security,
            TokenFileSource provisioningTokenSource) {
        return new VaultCredentialService(security.multiTenant().provisioningUrl(), provisioningTokenSource);
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
