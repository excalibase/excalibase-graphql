package io.github.excalibase.admin;

import io.github.excalibase.http.AdminPortFilter;
import io.github.excalibase.config.SecurityProperties;
import io.github.excalibase.metadata.AdminMetadataView;
import io.github.excalibase.metadata.AuthReport;
import io.github.excalibase.metadata.EngineAuth;
import io.github.excalibase.security.JwtService;
import io.github.excalibase.security.TokenVerifier;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;

import java.util.Map;

/**
 * The console's Auth page: how the engine checks tokens, and a way to try one. On the admin port only, behind the
 * admin secret, and only when {@code console.auth-page.enabled} is on; off, neither call exists. The pasted token is
 * read as raw bytes (a typed body could echo it in a binding error), checked, and dropped.
 */
@Controller(value = "/_admin/v1", port = "${excalibase.admin.port:10001}")
@Requires(property = "excalibase.console.enabled", pattern = AdminSettings.ON_PATTERN)
@Requires(property = "console.auth-page.enabled", pattern = AdminSettings.ON_PATTERN)
@Produces(MediaType.APPLICATION_JSON)
public class AdminAuthController {

    private static final String ERROR = "error";

    private final EngineAuth engineAuth;
    private final SecurityProperties security;
    private final AdminMetadataView metadata;
    private final AdminConfig.ValidationSettings validation;
    private final TokenCheckLimiter limiter;
    private final JwtService tokens;

    public AdminAuthController(EngineAuth engineAuth, SecurityProperties security, AdminMetadataView metadata,
                               AdminConfig.ValidationSettings validation, TokenCheckLimiter limiter,
                               @Nullable JwtService tokens) {
        this.engineAuth = engineAuth;
        this.security = security;
        this.metadata = metadata;
        this.validation = validation;
        this.limiter = limiter;
        this.tokens = tokens;
    }

    /** The token settings the engine runs with, redacted, and how the document's own section stands. */
    @Get("/auth")
    public Map<String, Object> settings() {
        SecurityProperties.Auth auth = security.auth();
        AuthReport.Legacy legacy = auth == null ? null
                : new AuthReport.Legacy(orEmpty(auth.jwksUrl()), orEmpty(auth.issuer()), auth.hasHmacSecret());
        return AuthReport.of(engineAuth, legacy, metadata.served().orElse(null), System::getenv);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Runs the pasted token through the verifier the engine serves with, and answers a reason, never a detail. */
    @Post("/auth/check")
    @Consumes(MediaType.ALL)
    public HttpResponse<Map<String, Object>> check(HttpRequest<?> request, @Body @Nullable byte[] token) {
        String address = AdminPortFilter.peer(request);
        if (!limiter.tryAcquire(address)) {
            return HttpResponse.<Map<String, Object>>status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", Long.toString(limiter.retryAfterSeconds(address)))
                    .body(Map.of(ERROR, "too_many_token_checks"));
        }
        if (token != null && token.length > TokenCheck.MAX_BYTES) {
            return HttpResponse.<Map<String, Object>>status(HttpStatus.REQUEST_ENTITY_TOO_LARGE)
                    .body(Map.of(ERROR, "token_too_large"));
        }
        String projectId = validation.projectId() == null || validation.projectId().isBlank() ? null : validation.projectId();
        TokenVerifier verifier = tokens == null ? null : (text, project) -> tokens.verify(text, project);
        return HttpResponse.ok(TokenCheck.run(verifier, token, projectId));
    }
}
