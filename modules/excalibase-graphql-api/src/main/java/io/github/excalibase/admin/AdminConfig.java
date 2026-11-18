package io.github.excalibase.admin;

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

    /** What {@code validate} needs to judge a document as the engine would. */
    public record ValidationSettings(ReservedSchemas reserved, String projectId, boolean envJwtConfigured) {
    }

    @Context
    AdminSettings adminSettings(
            @Value("${excalibase.console.enabled:false}") String enabled,
            @Value("${excalibase.admin.port:10001}") int port,
            @Value("${excalibase.admin.secret:}") String secret,
            @Value("${excalibase.admin.secret-file:}") String secretFile,
            @Value("${micronaut.server.port:-1}") int serverPort,
            @Value("${endpoints.all.port:-1}") int managementPort,
            @Value("${app.security.rls.policy-url:}") String policyUrl,
            @Value("${app.security.multi-tenant.provisioning-url:}") String provisioningUrl) {
        boolean hosted = !policyUrl.isBlank() || !provisioningUrl.isBlank();
        AdminSettings settings = AdminSettings.resolve(new AdminSettings.Raw(parse(enabled), port, secret, secretFile,
                serverPort, managementPort, hosted));
        if (settings.enabled()) {
            log.info("admin_console_enabled port={}", settings.port());
        }
        return settings;
    }

    private static boolean parse(String value) {
        String text = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return switch (text) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalStateException("EXCALIBASE_CONSOLE_ENABLED must be true or false but was '"
                    + value + "'");
        };
    }

    @Singleton
    @Requires(property = "excalibase.console.enabled", pattern = AdminSettings.ON_PATTERN)
    AdminGuard adminGuard(AdminSettings settings,
                          @Value("${excalibase.admin.max-failures:10}") int maxFailures,
                          @Value("${excalibase.admin.failure-window-seconds:60}") long windowSeconds) {
        return new AdminGuard(settings.secret(),
                new FailedAttempts(maxFailures, Duration.ofSeconds(windowSeconds), MAX_TRACKED_ADDRESSES, System::nanoTime));
    }

    @Singleton
    @Requires(property = "excalibase.console.enabled", pattern = AdminSettings.ON_PATTERN)
    ValidationSettings validationSettings(@Value("${app.reserved-schemas:}") String reserved,
                                          @Value("${app.project-id:}") String projectId,
                                          @Value("${app.security.auth.jwks-url:}") String jwksUrl,
                                          @Value("${app.security.auth.hmac-secret:}") String hmacSecret) {
        return new ValidationSettings(ReservedSchemas.fromConfig(reserved), projectId,
                !jwksUrl.isBlank() || !hmacSecret.isBlank());
    }
}
