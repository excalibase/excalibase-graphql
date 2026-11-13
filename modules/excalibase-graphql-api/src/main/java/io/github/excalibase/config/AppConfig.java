package io.github.excalibase.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.excalibase.compiler.ParsedQueries;
import io.github.excalibase.config.datasource.DynamicDataSourceManager;
import io.github.excalibase.config.datasource.TenantDataSourceFactory;
import io.github.excalibase.config.datasource.TenantDbSslMode;
import io.github.excalibase.cors.ProjectCorsConfigurationSource;
import io.github.excalibase.cors.ProjectCorsProvider;
import io.github.excalibase.cors.ProvisioningProjectCorsProvider;
import io.github.excalibase.observability.requests.RequestMetrics;
import io.github.excalibase.permissions.NoPermissionSource;
import io.github.excalibase.permissions.PermissionCachePolicy;
import io.github.excalibase.permissions.PermissionProvider;
import io.github.excalibase.permissions.ProvisioningPermissionProvider;
import io.github.excalibase.rls.PolicyChangeSubscriber;
import io.github.excalibase.schema.GraphqlSchemaManager;
import io.github.excalibase.security.JwtService;
import io.github.excalibase.security.KnownProjects;
import io.github.excalibase.security.TokenFileSource;
import io.github.excalibase.security.UnknownProjectCache;
import io.github.excalibase.service.VaultCredentialService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/** Wires the engine's beans. Property names are the engine's long-standing ones, read as before. */
@Factory
public class AppConfig {

    static final String NO_VERIFIER = "Authentication is on by default and needs a token verifier: set "
            + "app.security.auth.jwks-url or app.security.auth.hmac-secret. To run without authentication "
            + "for local development only, set app.security.jwt-enabled=false and app.security.insecure-dev-mode=true.";

    /**
     * Numbers keep every digit between the database and the client: a decimal in a request or in a row the
     * database answered is read as a BigDecimal (never a double) with its scale, and written back as plain digits.
     */
    @Singleton
    static class ExactJsonNumbers implements BeanCreatedEventListener<ObjectMapper> {
        @Override
        public ObjectMapper onCreated(BeanCreatedEvent<ObjectMapper> event) {
            ObjectMapper mapper = event.getBean();
            mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
            mapper.enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);
            mapper.setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
            return mapper;
        }
    }

    /** The single service-token source for every outbound call to the control plane. */
    @Singleton
    TokenFileSource provisioningTokenSource(
            @Value("${app.security.rls.policy-pat-file:}") String policyTokenFile,
            @Value("${app.security.multi-tenant.provisioning-pat-file:}") String tenantTokenFile,
            @Value("${app.security.rls.policy-pat:}") String policyToken,
            @Value("${app.security.multi-tenant.provisioning-pat:}") String tenantToken) {
        return new TokenFileSource(firstNonBlank(policyTokenFile, tenantTokenFile),
                firstNonBlank(policyToken, tenantToken));
    }

    @Singleton
    ProjectCorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:*}") String platformOrigins,
            @Value("${app.cors.provisioning-url:}") String corsProvisioningUrl,
            @Value("${app.security.rls.policy-url:}") String policyUrl,
            TokenFileSource provisioningTokenSource,
            @Value("${app.cors.ttl-ms:30000}") long ttlMillis) {
        String provisioningUrl = firstNonBlank(corsProvisioningUrl, policyUrl);
        ProjectCorsProvider provider = null;
        if (provisioningUrl != null && !provisioningUrl.isBlank()) {
            provider = new ProvisioningProjectCorsProvider(provisioningUrl, provisioningTokenSource, ttlMillis);
        }
        List<String> origins = Arrays.stream(platformOrigins.split(",")).map(String::trim)
                .filter(origin -> !origin.isEmpty()).toList();
        return new ProjectCorsConfigurationSource(provider, ProjectCorsConfigurationSource.configFor(origins));
    }

    @Singleton
    SecurityProperties securityProperties(
            @Value("${app.security.jwt-enabled:true}") boolean jwtEnabled,
            @Value("${app.security.auth.jwks-url:}") String jwksUrl,
            @Value("${app.security.auth.hmac-secret:}") String hmacSecret,
            @Value("${app.security.auth.jwks-ttl-minutes:}") String jwksTtlMinutes,
            @Value("${app.security.auth.issuer:}") String issuer,
            @Value("${app.security.auth.require-aud:true}") boolean requireAud,
            @Value("${app.security.auth.aud-prefix:excalibase:}") String audPrefix,
            @Value("${app.security.multi-tenant.provisioning-url:}") String provisioningUrl,
            @Value("${app.security.multi-tenant.provisioning-pat:}") String provisioningPat,
            @Value("${app.security.multi-tenant.provisioning-pat-file:}") String provisioningPatFile) {
        return new SecurityProperties(jwtEnabled,
                new SecurityProperties.Auth(jwksUrl, hmacSecret, jwksTtlMinutes, blankToNull(issuer), requireAud, audPrefix),
                new SecurityProperties.MultiTenant(provisioningUrl, provisioningPat, provisioningPatFile));
    }

    /** A property that falls back to another: written as two lookups, since nested defaults do not resolve empty. */
    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : (second == null ? "" : second);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @Context
    @Requires(property = "app.security.jwt-enabled", pattern = JwtEnabledSetting.ON_PATTERN)
    JwtService jwtService(SecurityProperties security, @Value("${app.cache.schema-ttl-minutes:30}") int ttlMinutes) {
        SecurityProperties.Auth auth = security.auth();
        boolean hasJwks = auth != null && auth.hasJwksUrl();
        boolean hasHmac = auth != null && auth.hasHmacSecret();
        if (hasJwks && hasHmac) {
            throw new IllegalStateException("app.security.auth: set either jwks-url or hmac-secret, not both");
        }
        if (!hasJwks && !hasHmac) {
            throw new IllegalStateException(NO_VERIFIER);
        }
        JwtService service = hasJwks ? new JwtService(auth.jwksUrl(), ttlMinutes) : new JwtService(auth.hmacSecret());
        return service.expectedIssuer(auth.issuer()).requireAudience(auth.requireAudOrDefault(), auth.audPrefix());
    }

    /**
     * Per-project permission documents, read from the control plane with the shared provisioning token. Without a
     * policy URL there is no source, and every read is refused rather than answered with an empty document.
     */
    @Singleton
    @Requires(property = "app.security.jwt-enabled", pattern = JwtEnabledSetting.ON_PATTERN)
    PermissionProvider permissionProvider(
            @Value("${app.security.rls.policy-url:}") String policyUrl,
            TokenFileSource provisioningTokenSource,
            @Value("${app.security.rls.policy-ttl-ms:30000}") long policyTtlMs,
            @Value("${app.security.permissions.max-stale-ms:300000}") long maxStaleMs,
            @Value("${app.security.permissions.retry-interval-ms:2000}") long retryIntervalMs,
            @Nullable MeterRegistry meterRegistry) {
        PermissionCachePolicy policy = new PermissionCachePolicy(policyTtlMs, maxStaleMs, retryIntervalMs);
        if (policyUrl != null && !policyUrl.isBlank()) {
            return new ProvisioningPermissionProvider(policyUrl, provisioningTokenSource, policy,
                    meterRegistry != null ? meterRegistry : new SimpleMeterRegistry());
        }
        return new NoPermissionSource();
    }

    /** Invalidates a project's cached permissions and engines on a NATS {@code policies.{id}.changed} signal. */
    @Context
    @Requires(property = "app.security.jwt-enabled", pattern = JwtEnabledSetting.ON_PATTERN)
    PolicyChangeSubscriber policyChangeSubscriber(
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

    /** The tenant TLS mode, read at start in every mode so a weaker one is refused even when no tenant is served. */
    @Context
    TenantDbSslMode tenantDbSslMode(@Value("${app.tenant-db.sslmode:verify-full}") String tenantSslMode) {
        return TenantDbSslMode.parse(tenantSslMode);
    }

    /** Per-project database routing, present only when a provisioning URL is set. */
    @Singleton
    @Requires(property = "app.security.multi-tenant.provisioning-url", pattern = ".+")
    DynamicDataSourceManager dynamicDataSourceManager(SecurityProperties security,
            TokenFileSource provisioningTokenSource,
            @Value("${app.cache.schema-ttl-minutes:30}") int ttlMinutes,
            @Value("${app.hikari.tenant-pool-size:5}") int poolSize,
            TenantDbSslMode sslMode,
            @Value("${app.tenant-db.read-timeout-seconds:60}") int readTimeoutSeconds) {
        VaultCredentialService vault = new VaultCredentialService(
                security.multiTenant().provisioningUrl(), provisioningTokenSource);
        return new DynamicDataSourceManager(vault,
                new TenantDataSourceFactory(sslMode, poolSize, Duration.ofSeconds(readTimeoutSeconds)), ttlMinutes);
    }

    @Singleton
    KnownProjects knownProjects(
            @Value("${app.project-id:}") String projectId,
            @Value("${app.security.multi-tenant.provisioning-url:}") String tenantProvisioningUrl,
            @Nullable DynamicDataSourceManager dataSourceManager,
            @Value("${app.security.multi-tenant.unknown-project-cache.max-entries:10000}") int unknownMaxEntries,
            @Value("${app.security.multi-tenant.unknown-project-cache.ttl-seconds:30}") long unknownTtlSeconds) {
        UnknownProjectCache unknown = new UnknownProjectCache(unknownMaxEntries,
                Duration.ofSeconds(unknownTtlSeconds), System::currentTimeMillis);
        return KnownProjects.forDeployment(projectId, tenantProvisioningUrl, dataSourceManager, unknown);
    }

    /** {@code app.graphql.parse-cache.heap-share}: the share of the maximum heap the parsed-query cache may use. */
    @Singleton
    ParsedQueries parsedQueries(@Value("${app.graphql.parse-cache.heap-share:0.02}") double heapShare) {
        ParsedQueries.requireValidHeapShare(heapShare);
        return ParsedQueries.forHeapShare(heapShare, Runtime.getRuntime().maxMemory());
    }

    /** Per-project request metrics, only with {@code app.request-metrics.enabled}. */
    @Singleton
    @Requires(property = "app.request-metrics.enabled", value = "true")
    RequestMetrics requestMetrics(@Value("${app.request-metrics.max-projects:1000}") int maxProjects) {
        if (maxProjects < 1) {
            throw new IllegalStateException("app.request-metrics.max-projects must be at least 1");
        }
        return new RequestMetrics(maxProjects);
    }

    /** Observations record the {@code graphql.request} timer through the meter registry, as before. */
    @Singleton
    ObservationRegistry observationRegistry(MeterRegistry meterRegistry) {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meterRegistry));
        return registry;
    }
}
