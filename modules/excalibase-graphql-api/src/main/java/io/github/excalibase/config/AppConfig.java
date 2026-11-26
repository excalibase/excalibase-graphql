package io.github.excalibase.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.excalibase.compiler.ParsedQueries;
import io.github.excalibase.config.datasource.DataSourceConfig;
import io.github.excalibase.config.datasource.DynamicDataSourceManager;
import io.github.excalibase.config.ws.WsRuntime;
import io.github.excalibase.config.datasource.TenantDataSourceFactory;
import io.github.excalibase.config.datasource.TenantDbSslMode;
import io.github.excalibase.cors.ProjectCorsConfigurationSource;
import io.github.excalibase.cors.ProjectCorsProvider;
import io.github.excalibase.cors.ProvisioningProjectCorsProvider;
import io.github.excalibase.observability.requests.RequestMetrics;
import io.github.excalibase.permissions.NoPermissionSource;
import io.github.excalibase.permissions.PermissionCachePolicy;
import io.github.excalibase.permissions.PermissionVersions;
import io.github.excalibase.metadata.AuthSettingsReader;
import io.github.excalibase.metadata.DbSettings;
import io.github.excalibase.metadata.EngineAuth;
import io.github.excalibase.metadata.MetadataHealth;
import io.github.excalibase.metadata.MetadataSettings;
import io.github.excalibase.metadata.MetadataSource;
import io.github.excalibase.metadata.MetadataSources;
import io.github.excalibase.metadata.S3Settings;
import io.github.excalibase.metadata.UrlSettings;
import io.github.excalibase.permissions.PermissionProvider;
import io.github.excalibase.permissions.ProvisioningPermissionProvider;
import io.github.excalibase.rls.PolicyChangeSubscriber;
import io.github.excalibase.schema.GraphqlSchemaManager;
import io.github.excalibase.security.JwtService;
import io.github.excalibase.security.ClientLookups;
import io.github.excalibase.security.KnownProjects;
import io.github.excalibase.security.LookupLimiter;
import io.github.excalibase.security.TokenFileSource;
import io.github.excalibase.security.TokenVerifier;
import io.github.excalibase.security.UnknownProjectCache;
import io.github.excalibase.service.VaultCredentialService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.env.Environment;
import io.micronaut.core.naming.conventions.StringConvention;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/** Wires the engine's beans. Property names are the engine's long-standing ones, read as before. */
@Factory
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);
    /** Client addresses whose lookup allowance is held at once. */
    private static final int CLIENTS_HELD = 10_000;

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
            @Value("${app.cors.allowed-origins:}") String allowedOrigins,
            @Value("${CORS_ORIGINS:}") String platformAioOrigins,
            @Value("${app.cors.provisioning-url:}") String corsProvisioningUrl,
            @Value("${app.security.rls.policy-url:}") String policyUrl,
            TokenFileSource provisioningTokenSource,
            @Value("${app.cors.ttl-ms:30000}") long ttlMillis) {
        String provisioningUrl = firstNonBlank(corsProvisioningUrl, policyUrl);
        ProjectCorsProvider provider = null;
        if (provisioningUrl != null && !provisioningUrl.isBlank()) {
            provider = new ProvisioningProjectCorsProvider(provisioningUrl, provisioningTokenSource, ttlMillis);
        }
        List<String> origins = Arrays.stream(platformOrigins(allowedOrigins, platformAioOrigins).split(",")).map(String::trim)
                .filter(origin -> !origin.isEmpty()).toList();
        return new ProjectCorsConfigurationSource(provider, ProjectCorsConfigurationSource.configFor(origins));
    }

    /** {@code APP_CORS_ALLOWED_ORIGINS}, else {@code CORS_ORIGINS} (the name platform-aio sets), else any origin. */
    static String platformOrigins(String allowedOrigins, String platformAioOrigins) {
        String origins = firstNonBlank(allowedOrigins, platformAioOrigins);
        return origins.isBlank() ? "*" : origins;
    }

    @Singleton
    SecurityProperties securityProperties(
            JwtEnabledSetting jwtEnabledSetting,
            @Value("${app.security.auth.jwks-url:}") String jwksUrl,
            @Value("${app.security.auth.hmac-secret:}") String hmacSecret,
            @Value("${app.security.auth.jwks-ttl-minutes:}") String jwksTtlMinutes,
            @Value("${app.security.auth.issuer:}") String issuer,
            @Value("${app.security.auth.require-aud:true}") boolean requireAud,
            @Value("${app.security.auth.aud-prefix:excalibase:}") String audPrefix,
            @Value("${app.security.multi-tenant.provisioning-url:}") String provisioningUrl,
            @Value("${app.security.multi-tenant.provisioning-pat:}") String provisioningPat,
            @Value("${app.security.multi-tenant.provisioning-pat-file:}") String provisioningPatFile) {
        return new SecurityProperties(jwtEnabledSetting.enabled(),
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

    /**
     * The engine's own token settings ({@code app.auth.*}), or the legacy ones, or none. Eager, so a refusal (an
     * unknown name, hosted mode, authentication switched off, both kinds set) stops start-up whatever else is on.
     */
    @Context
    EngineAuth engineAuth(Environment environment, JwtEnabledSetting jwtEnabledSetting,
                          @Value("${app.security.rls.policy-url:}") String policyUrl,
                          @Value("${app.security.multi-tenant.provisioning-url:}") String provisioningUrl,
                          @Value("${app.security.auth.jwks-url:}") String legacyJwksUrl,
                          @Value("${app.security.auth.hmac-secret:}") String legacyHmacSecret) {
        boolean hosted = !policyUrl.isBlank() || !provisioningUrl.isBlank();
        boolean legacy = !legacyJwksUrl.isBlank() || !legacyHmacSecret.isBlank();
        return AuthSettingsReader.resolve(new AuthSettingsReader.Inputs(hosted, jwtEnabledSetting.enabled(), legacy,
                System.getenv().keySet(), environment.getProperties("app.auth", StringConvention.RAW).keySet(),
                key -> environment.getProperty("app.auth." + key, String.class).orElse(null)));
    }

    /** Kept for callers with no engine settings of their own. */
    JwtService jwtService(SecurityProperties security, int ttlMinutes, @Nullable PermissionProvider permissionSource) {
        return jwtService(security, ttlMinutes, permissionSource, EngineAuth.NONE);
    }

    /**
     * With {@code metadata.source} set, the token settings may come from the document instead of the engine's own
     * (EXC-733): the service then asks the served document per token. The parser refuses a document that adds a
     * {@code jwt:} section while the engine has settings of its own, unless it is identical to {@code app.auth.*}.
     * With {@code app.auth.*} the engine's verifier is the only one. With no metadata source nothing here differs.
     */
    @Context
    @Requires(property = "app.security.jwt-enabled", pattern = JwtEnabledSetting.ON_PATTERN)
    JwtService jwtService(SecurityProperties security, @Value("${app.cache.schema-ttl-minutes:30}") int ttlMinutes,
                          @Nullable PermissionProvider permissionSource, EngineAuth engineAuth) {
        MetadataSource metadata = permissionSource instanceof MetadataSource source ? source : null;
        SecurityProperties.Auth auth = security.auth();
        boolean hasJwks = auth != null && auth.hasJwksUrl();
        boolean hasHmac = auth != null && auth.hasHmacSecret();
        if (hasJwks && hasHmac) {
            throw new IllegalStateException("app.security.auth: set either jwks-url or hmac-secret, not both");
        }
        if (engineAuth.engine()) {
            return engineService(engineAuth, hasJwks || hasHmac);
        }
        if (!hasJwks && !hasHmac) {
            return documentService(metadata);
        }
        return legacyService(security, ttlMinutes, metadata, hasJwks);
    }

    private static JwtService engineService(EngineAuth engineAuth, boolean legacyAlsoSet) {
        if (legacyAlsoSet) {
            throw new IllegalStateException(AuthSettingsReader.BOTH_REFUSAL);
        }
        TokenVerifier verifier = engineAuth.verifier();
        return JwtService.documentOnly().documentVerifier(() -> verifier);
    }

    private static JwtService documentService(@Nullable MetadataSource metadata) {
        if (metadata == null) {
            throw new IllegalStateException(NO_VERIFIER);
        }
        warnWhenNothingVerifiesTokens(metadata);
        return JwtService.documentOnly().documentVerifier(() -> metadata.tokenVerifier().orElse(null));
    }

    private static JwtService legacyService(SecurityProperties security, int ttlMinutes,
                                            @Nullable MetadataSource metadata, boolean hasJwks) {
        SecurityProperties.Auth auth = security.auth();
        requireAudienceWhenMultiTenant(security.multiTenant() == null ? null : security.multiTenant().provisioningUrl(),
                auth.requireAudOrDefault());
        JwtService service = hasJwks ? new JwtService(auth.jwksUrl(), ttlMinutes) : new JwtService(auth.hmacSecret());
        service.expectedIssuer(auth.issuer()).requireAudience(auth.requireAudOrDefault(), auth.audPrefix());
        if (metadata != null) {
            service.documentVerifier(() -> metadata.tokenVerifier().orElse(null));
        }
        return service;
    }

    /**
     * A document with rules for a role besides anon and no verifier anywhere starts (an anon-only deployment is
     * valid) but rejects every token. Said loudly once at start; the validate warning says it on every edit.
     */
    private static void warnWhenNothingVerifiesTokens(MetadataSource metadata) {
        metadata.current().filter(snapshot -> snapshot.verifier() == null)
                .filter(snapshot -> snapshot.permissions().tables().stream().anyMatch(rule -> !"anon".equals(rule.role())))
                .ifPresent(_ -> log.warn("no_token_verifier the metadata document has rules for a role other than anon "
                        + "but no token verifier is configured (app.auth.*, app.security.auth.* or a jwt: section): "
                        + "every token is rejected"));
    }

    /**
     * Without the audience check a token minted for one project passes the path check of every other, which
     * a deployment serving many projects cannot allow.
     */
    static void requireAudienceWhenMultiTenant(String provisioningUrl, boolean requireAudience) {
        if (provisioningUrl != null && !provisioningUrl.isBlank() && !requireAudience) {
            throw new IllegalStateException("app.security.auth.require-aud=false is refused in multi-tenant mode "
                    + "(app.security.multi-tenant.provisioning-url): a token for one project would open every other");
        }
    }

    /**
     * The permission source. With {@code metadata.source} unset this is exactly the provisioning-or-nothing choice
     * below; with it set the named metadata source is used instead (EXC-733).
     */
    @Singleton
    @Requires(property = "app.security.jwt-enabled", pattern = JwtEnabledSetting.ON_PATTERN)
    PermissionProvider permissionSource(
            @Value("${metadata.source:}") String metadataSource,
            @Value("${app.security.rls.policy-url:}") String policyUrl,
            @Value("${metadata.file.path:}") String metadataFilePath,
            @Value("${metadata.file.poll-interval-ms:5000}") long pollIntervalMs,
            @Value("${metadata.url.location:}") String urlLocation,
            @Value("${metadata.url.token:}") String urlToken,
            @Value("${metadata.url.token-file:}") String urlTokenFile,
            @Value("${metadata.url.allow-insecure:false}") boolean urlAllowInsecure,
            @Value("${metadata.url.poll-interval-ms:5000}") long urlPollIntervalMs,
            @Value("${metadata.url.connect-timeout-ms:3000}") long urlConnectTimeoutMs,
            @Value("${metadata.url.read-timeout-ms:10000}") long urlReadTimeoutMs,
            @Value("${metadata.database.url:}") String dbUrl,
            @Value("${metadata.database.username:}") String dbUsername,
            @Value("${metadata.database.password:}") String dbPassword,
            @Value("${metadata.db.migrate:false}") boolean dbMigrate,
            @Value("${metadata.db.poll-interval-ms:5000}") long dbPollIntervalMs,
            @Value("${metadata.db.connect-timeout-ms:3000}") long dbConnectTimeoutMs,
            DataSourceConfig.DataSourceSettings servedDatabase,
            S3Settings s3Settings,
            @Value("${app.project-id:}") String projectId,
            @Value("${app.reserved-schemas:}") String reservedSchemas,
            EngineAuth engineAuth,
            TokenFileSource provisioningTokenSource,
            @Value("${app.security.rls.policy-ttl-ms:30000}") long policyTtlMs,
            @Value("${app.security.permissions.max-stale-ms:300000}") long maxStaleMs,
            @Value("${app.security.permissions.retry-interval-ms:2000}") long retryIntervalMs,
            @Nullable MeterRegistry meterRegistry) {
        MeterRegistry meters = meterRegistry != null ? meterRegistry : new SimpleMeterRegistry();
        UrlSettings url = new UrlSettings(urlLocation, urlToken, urlTokenFile, urlAllowInsecure,
                Duration.ofMillis(urlPollIntervalMs), Duration.ofMillis(urlConnectTimeoutMs),
                Duration.ofMillis(urlReadTimeoutMs));
        MetadataSettings settings = new MetadataSettings(metadataSource, policyUrl, projectId, reservedSchemas,
                new MetadataSettings.FileSettings(metadataFilePath, pollIntervalMs), url,
                metadataDatabase(dbUrl, dbUsername, dbPassword, dbMigrate, dbPollIntervalMs, dbConnectTimeoutMs,
                        servedDatabase),
                s3Settings,
                engineAuth);
        return MetadataSources.create(settings, meters)
                .<PermissionProvider>map(source -> source)
                .orElseGet(() -> permissionProvider(policyUrl, provisioningTokenSource, policyTtlMs, maxStaleMs,
                        retryIntervalMs, meterRegistry));
    }

    /** {@code METADATA_DATABASE_URL} with its own credentials, or else the served database and its credentials. */
    static DbSettings metadataDatabase(String url, String username, String password, boolean migrate,
                                       long pollIntervalMs, long connectTimeoutMs,
                                       DataSourceConfig.DataSourceSettings served) {
        boolean separate = url != null && !url.isBlank();
        return new DbSettings(separate ? url : served.url(), separate ? username : served.username(),
                separate ? password : served.password(), migrate, Duration.ofMillis(pollIntervalMs),
                Duration.ofMillis(connectTimeoutMs));
    }

    /** What the actuators report about the permission source when it is a metadata source. */
    @Singleton
    @Requires(property = "app.security.jwt-enabled", pattern = JwtEnabledSetting.ON_PATTERN)
    MetadataHealth metadataHealth(PermissionProvider permissionSource) {
        return new MetadataHealth(permissionSource);
    }

    /**
     * Per-project permission documents, read from the control plane with the shared provisioning token. Without a
     * policy URL there is no source, and every read is refused rather than answered with an empty document.
     */
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
    @Bean(preDestroy = "stop")
    @Requires(property = "app.security.jwt-enabled", pattern = JwtEnabledSetting.ON_PATTERN)
    PolicyChangeSubscriber policyChangeSubscriber(
            PermissionProvider permissionProvider,
            GraphqlSchemaManager schemaManager,
            WsRuntime webSockets,
            @Value("${app.nats.enabled:false}") boolean natsEnabled,
            @Value("${app.nats.url:nats://localhost:4222}") String natsUrl,
            @Value("${app.nats.username:}") String natsUsername,
            @Value("${app.nats.password:}") String natsPassword,
            @Value("${app.nats.inbox-prefix:}") String natsInboxPrefix) {
        permissionProvider.onChange(webSockets::permissionsAre);
        PolicyChangeSubscriber subscriber = new PolicyChangeSubscriber(List.of(permissionProvider, schemaManager::evict),
                new PermissionVersions(permissionProvider), webSockets, natsEnabled, natsUrl, natsUsername, natsPassword, natsInboxPrefix,
                () -> {
                    permissionProvider.evictAll();
                    schemaManager.evictAll();
                });
        subscriber.start();   // Micronaut runs no @PostConstruct on a factory-built bean
        return subscriber;
    }

    /** The tenant TLS mode, read at start in every mode so a weaker one is refused even when no tenant is served. */
    @Context
    TenantDbSslMode tenantDbSslMode(@Value("${app.tenant-db.sslmode:verify-full}") String tenantSslMode) {
        return TenantDbSslMode.parse(tenantSslMode);
    }

    /** Per-project database routing, present only when a provisioning URL is set. */
    @Singleton
    @Bean(preDestroy = "destroy")
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
            @Value("${app.security.multi-tenant.unknown-project-cache.ttl-seconds:30}") long unknownTtlSeconds,
            @Value("${app.security.multi-tenant.lookup-rate-per-second:50}") int lookupsPerSecond,
            @Value("${app.security.multi-tenant.lookup-rate-per-client:10}") int lookupsPerClient) {
        UnknownProjectCache unknown = new UnknownProjectCache(unknownMaxEntries,
                Duration.ofSeconds(unknownTtlSeconds), System::currentTimeMillis);
        return KnownProjects.forDeployment(projectId, tenantProvisioningUrl, dataSourceManager, unknown,
                LookupLimiter.perSecond(lookupsPerSecond), ClientLookups.perSecond(lookupsPerClient, CLIENTS_HELD));
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
