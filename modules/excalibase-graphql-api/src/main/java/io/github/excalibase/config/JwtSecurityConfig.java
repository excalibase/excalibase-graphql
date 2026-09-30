package io.github.excalibase.config;

import io.github.excalibase.config.datasource.DynamicDataSourceManager;
import io.github.excalibase.config.datasource.TenantDataSourceFactory;
import io.github.excalibase.config.datasource.TenantDbSslMode;
import io.github.excalibase.permissions.NoPermissionSource;
import io.github.excalibase.permissions.PermissionCachePolicy;
import io.github.excalibase.permissions.PermissionProvider;
import io.github.excalibase.permissions.ProvisioningPermissionProvider;
import io.github.excalibase.rls.PolicyChangeSubscriber;
import io.github.excalibase.schema.AccessPlans;
import io.github.excalibase.schema.GraphqlSchemaManager;
import io.github.excalibase.security.JwtAuthFilter;
import io.github.excalibase.security.JwtService;
import io.github.excalibase.security.TokenFileSource;
import io.github.excalibase.service.VaultCredentialService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@ConditionalOnProperty(name = "app.security.jwt-enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SecurityProperties.class)
public class JwtSecurityConfig {

    static final String NO_VERIFIER = "Authentication is on by default and needs a token verifier: set "
            + "app.security.auth.jwks-url or app.security.auth.hmac-secret. To run without authentication "
            + "for local development only, set app.security.jwt-enabled=false and app.security.insecure-dev-mode=true.";

    @Bean
    public JwtService jwtService(
            SecurityProperties security,
            @Value("${app.cache.schema-ttl-minutes:30}") int ttlMinutes) {

        SecurityProperties.Auth auth = security.auth();
        boolean hasJwks = auth != null && auth.hasJwksUrl();
        boolean hasHmac = auth != null && auth.hasHmacSecret();

        if (hasJwks && hasHmac) {
            throw new IllegalStateException(
                    "app.security.auth: set either jwks-url or hmac-secret, not both");
        }
        if (!hasJwks && !hasHmac) {
            throw new IllegalStateException(NO_VERIFIER);
        }

        JwtService service = hasJwks
                ? new JwtService(auth.jwksUrl(), ttlMinutes)
                : new JwtService(auth.hmacSecret());

        return service
                .expectedIssuer(auth.issuer())
                .requireAudience(auth.requireAudOrDefault(), auth.audPrefix());
    }

    /**
     * Per-project permission documents (docs/features/permissions.md §8), read from the control plane
     * with the shared provisioning token and cached for the policy TTL. Through a control-plane outage the
     * cached copy is served for at most max-stale, then every role but service gets 503; the control plane
     * is asked at most once per retry interval meanwhile. Without a policy
     * URL there is no source, and every read is refused rather than answered with an empty document.
     */
    @Bean
    public PermissionProvider permissionProvider(
            @Value("${app.security.rls.policy-url:}") String policyUrl,
            TokenFileSource provisioningTokenSource,
            @Value("${app.security.rls.policy-ttl-ms:30000}") long policyTtlMs,
            @Value("${app.security.permissions.max-stale-ms:300000}") long maxStaleMs,
            @Value("${app.security.permissions.retry-interval-ms:2000}") long retryIntervalMs,
            ObjectProvider<MeterRegistry> meterRegistry) {
        PermissionCachePolicy policy = new PermissionCachePolicy(policyTtlMs, maxStaleMs, retryIntervalMs);
        if (policyUrl != null && !policyUrl.isBlank()) {
            return new ProvisioningPermissionProvider(policyUrl, provisioningTokenSource, policy,
                    meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
        }
        return new NoPermissionSource();
    }

    @Bean
    public JwtAuthFilter jwtAuthFilter(JwtService jwtService, AccessPlans accessPlans) {
        return new JwtAuthFilter(jwtService, accessPlans);
    }

    /**
     * Invalidates a project's cached permissions and engines on a NATS {@code policies.{id}.changed}
     * signal from provisioning, so Studio edits converge immediately rather than waiting out the
     * cache TTL (which stays as the fail-safe). Enablement is read at runtime from
     * {@code app.nats.enabled}; disabled = safe no-op.
     */
    @Bean
    public PolicyChangeSubscriber policyChangeSubscriber(
            PermissionProvider permissionProvider,
            GraphqlSchemaManager schemaManager,
            @Value("${app.nats.enabled:false}") boolean natsEnabled,
            @Value("${app.nats.url:nats://localhost:4222}") String natsUrl,
            @Value("${app.nats.username:}") String natsUsername,
            @Value("${app.nats.password:}") String natsPassword,
            @Value("${app.nats.inbox-prefix:}") String natsInboxPrefix) {
        return new PolicyChangeSubscriber(List.of(permissionProvider, schemaManager::evict),
                natsEnabled, natsUrl, natsUsername, natsPassword, natsInboxPrefix);
    }

    /**
     * Per-project database routing, present only when
     * {@code app.security.multi-tenant.provisioning-url} is set to something. Decided
     * at runtime like {@link #permissionProvider}: {@code @ConditionalOnProperty} would
     * also match the blank default and route single-database requests to a vault
     * that does not exist. A null bean leaves every optional injection point empty.
     */
    @Bean
    public DynamicDataSourceManager dynamicDataSourceManager(SecurityProperties security,
            TokenFileSource provisioningTokenSource,
            @Value("${app.cache.schema-ttl-minutes:30}") int ttlMinutes,
            @Value("${app.hikari.tenant-pool-size:5}") int poolSize,
            @Value("${app.tenant-db.sslmode:verify-full}") String tenantSslMode) {
        TenantDbSslMode sslMode = TenantDbSslMode.parse(tenantSslMode);
        if (!security.isMultiTenantEnabled()) {
            return null;
        }
        VaultCredentialService vault = new VaultCredentialService(
                security.multiTenant().provisioningUrl(), provisioningTokenSource);
        return new DynamicDataSourceManager(vault, new TenantDataSourceFactory(sslMode, poolSize), ttlMinutes);
    }
}
