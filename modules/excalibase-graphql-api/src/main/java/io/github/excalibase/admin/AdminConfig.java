package io.github.excalibase.admin;

import io.github.excalibase.metadata.EngineAuth;
import io.github.excalibase.schema.ReservedSchemas;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Locale;

/**
 * Wires the admin console. {@code EXCALIBASE_CONSOLE_ENABLED} is read here once, strictly: only true or false, so a
 * misspelling cannot leave the console half on. Off, there is no guard, no controller and no admin listener.
 */
@Factory
public class AdminConfig {

    private static final Logger log = LoggerFactory.getLogger(AdminConfig.class);
    private static final int MAX_TRACKED_ADDRESSES = 10_000;
    static final String CONSOLE_PROPERTY = "excalibase.console.enabled";
    private static final String CONSOLE_VARIABLE = "EXCALIBASE_CONSOLE_ENABLED";
    private static final String AUTH_PAGE_VARIABLE = "CONSOLE_AUTH_PAGE_ENABLED";

    /** What {@code validate} needs to judge a document as the engine would. */
    public record ValidationSettings(ReservedSchemas reserved, String projectId, EngineAuth engineAuth) {

        public ValidationSettings(ReservedSchemas reserved, String projectId, boolean envJwtConfigured) {
            this(reserved, projectId, EngineAuth.of(envJwtConfigured));
        }
    }

    /** Whether this deployment serves the hosted platform: a policy URL or a provisioning URL is set. */
    record Hosted(boolean active) {
    }

    @Singleton
    Hosted hosted(@Value("${app.security.rls.policy-url:}") String policyUrl,
                  @Value("${app.security.multi-tenant.provisioning-url:}") String provisioningUrl) {
        return new Hosted(!policyUrl.isBlank() || !provisioningUrl.isBlank());
    }

    @Context
    AdminSettings adminSettings(
            @Value("${excalibase.console.enabled:false}") String enabled,
            @Value("${excalibase.admin.port:10001}") int port,
            @Value("${excalibase.admin.secret:}") String secret,
            @Value("${excalibase.admin.secret-file:}") String secretFile,
            @Value("${micronaut.server.port:-1}") int serverPort,
            @Value("${endpoints.all.port:-1}") int managementPort,
            Hosted hosted) {
        AdminSettings settings = AdminSettings.resolve(new AdminSettings.Raw(parse(CONSOLE_VARIABLE, enabled), port, secret,
                secretFile, serverPort, managementPort, hosted.active()));
        if (settings.enabled()) {
            log.info("admin_console_enabled port={}", settings.port());
        }
        return settings;
    }

    /** Whether the Auth page is on. Eager and strict like the console flag, and refused without the console: it would do nothing. */
    record AuthPage(boolean enabled) {
    }

    @Context
    AuthPage authPage(@Value("${console.auth-page.enabled:false}") String enabled, AdminSettings settings) {
        boolean on = parse(AUTH_PAGE_VARIABLE, enabled);
        if (on && !settings.enabled()) {
            throw new IllegalStateException(AUTH_PAGE_VARIABLE + "=true needs " + CONSOLE_VARIABLE + "=true: the Auth page "
                    + "is part of the console, so the setting would do nothing");
        }
        return new AuthPage(on);
    }

    private static boolean parse(String name, String value) {
        String text = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return switch (text) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalStateException(name + " must be true or false but was '" + value + "'");
        };
    }

    @Singleton
    @Requires(property = CONSOLE_PROPERTY, pattern = AdminSettings.ON_PATTERN)
    AdminGuard adminGuard(AdminSettings settings,
                          @Value("${excalibase.admin.max-failures:10}") int maxFailures,
                          @Value("${excalibase.admin.failure-window-seconds:60}") long windowSeconds) {
        return new AdminGuard(settings.secret(),
                new AdminGuard.FailedAttempts(maxFailures, Duration.ofSeconds(windowSeconds), MAX_TRACKED_ADDRESSES, System::nanoTime));
    }

    /** Its own limit and its own counts: the admin guard's attempts reset on a right secret, this never does. */
    @Singleton
    @Requires(property = CONSOLE_PROPERTY, pattern = AdminSettings.ON_PATTERN)
    @Requires(property = "console.auth-page.enabled", pattern = AdminSettings.ON_PATTERN)
    TokenCheckLimiter tokenCheckLimiter(@Value("${excalibase.admin.token-check-limit:30}") int limit,
                                        @Value("${excalibase.admin.token-check-window-seconds:60}") long windowSeconds) {
        return new TokenCheckLimiter(limit, Duration.ofSeconds(windowSeconds), MAX_TRACKED_ADDRESSES, System::nanoTime);
    }

    @Singleton
    @Requires(property = CONSOLE_PROPERTY, pattern = AdminSettings.ON_PATTERN)
    ValidationSettings validationSettings(@Value("${app.reserved-schemas:}") String reserved,
                                          @Value("${app.project-id:}") String projectId,
                                          EngineAuth engineAuth) {
        return new ValidationSettings(ReservedSchemas.fromConfig(reserved), projectId, engineAuth);
    }
}
