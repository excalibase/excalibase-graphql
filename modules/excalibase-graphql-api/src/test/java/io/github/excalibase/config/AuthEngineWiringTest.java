package io.github.excalibase.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.excalibase.security.JwtService;
import io.github.excalibase.security.JwtVerificationException;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import io.github.excalibase.metadata.MetadataSource;
import io.github.excalibase.permissions.PermissionProvider;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The engine's own token settings, through a real start-up. */
class AuthEngineWiringTest {

    private static final String SECRET = "an-hmac-secret-of-at-least-32-bytes!!";
    private static final String OTHER = "another-hmac-secret-of-32-bytes-or-more";
    private static final String RULES = """
            tables:
              - table: public.orders
                role: user
                select:
                  filter: {}
                  columns: "*"
            """;

    private static Map<String, Object> engine(Map<String, Object> extra) {
        Map<String, Object> properties = new HashMap<>(Map.of(
                "spring.datasource.url", "jdbc:postgresql://localhost:59999/nonexistent",
                "spring.datasource.username", "fake",
                "spring.datasource.password", "fake",
                "app.project-id", "demo",
                "app.nats.enabled", "false",
                "micronaut.server.port", "-1",
                "endpoints.all.port", "-1"));
        properties.putAll(extra);
        return properties;
    }

    private static Map<String, Object> appAuth() {
        return Map.of("app.auth.secret", SECRET, "app.auth.audience", "my-api");
    }

    private static Map<String, Object> with(Map<String, Object> first, Map<String, Object> second) {
        Map<String, Object> merged = new HashMap<>(first);
        merged.putAll(second);
        return merged;
    }

    private static String token(String secret) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder().subject("u-1")
                .audience("my-api").claim("role", "user").expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build());
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private static Path file(Path dir, String jwt) throws Exception {
        Path file = dir.resolve("permissions.yaml");
        Files.writeString(file, "format: 1\nprojectId: demo\n" + jwt + RULES);
        return file;
    }

    private static Map<String, Object> withFile(Path file) {
        return Map.of("metadata.source", "file", "metadata.file.path", file.toString());
    }

    @Test
    @DisplayName("app.auth.* with a metadata file: its secret verifies tokens, another secret does not")
    void engineSettingsVerifyTokens(@TempDir Path dir) throws Exception {
        try (ApplicationContext context = ApplicationContext.run(with(with(engine(appAuth()), withFile(file(dir, ""))),
                Map.of()))) {
            JwtService service = context.getBean(JwtService.class);

            String own = token(SECRET);
            String foreign = token(OTHER);

            assertThat(service.verify(own, "demo").userId()).isEqualTo("u-1");
            assertThatThrownBy(() -> service.verify(foreign, "demo")).isInstanceOf(JwtVerificationException.class);
        }
    }

    @Test
    @DisplayName("a document jwt: that differs is refused, never served, and the engine's key still decides tokens")
    void differingDocumentSectionIsRefused(@TempDir Path dir) throws Exception {
        Path file = file(dir, "jwt:\n  secret: file:" + Files.writeString(dir.resolve("old"), OTHER)
                + "\n  audience: my-api\n");

        try (ApplicationContext context = ApplicationContext.run(with(engine(appAuth()), withFile(file)))) {
            MetadataSource source = (MetadataSource) context.getBean(PermissionProvider.class);
            JwtService service = context.getBean(JwtService.class);

            assertThat(source.current()).isEmpty();
            assertThat(source.status().lastRefusal()).isNotNull();
            String own = token(SECRET);
            String foreign = token(OTHER);

            assertThat(service.verify(own, "demo").userId()).isEqualTo("u-1");
            assertThatThrownBy(() -> service.verify(foreign, "demo")).isInstanceOf(JwtVerificationException.class);
        }
    }

    @Test
    @DisplayName("an identical document jwt: starts, with only the engine verifier")
    void identicalDocumentSectionStarts(@TempDir Path dir) throws Exception {
        Path file = file(dir, "jwt:\n  secret: file:" + Files.writeString(dir.resolve("same"), SECRET)
                + "\n  audience: my-api\n");

        try (ApplicationContext context = ApplicationContext.run(with(engine(appAuth()), withFile(file)))) {
            assertThat(context.getBean(JwtService.class).verify(token(SECRET), "demo").userId()).isEqualTo("u-1");
        }
    }

    @Test
    @DisplayName("app.auth.* with authentication off refuses start")
    void withAuthenticationOff() {
        Map<String, Object> off = with(engine(appAuth()),
                Map.of("app.security.jwt-enabled", "false", "app.security.insecure-dev-mode", "true"));

        assertThatThrownBy(() -> ApplicationContext.run(off).close())
                .hasStackTraceContaining("app.security.jwt-enabled is false");
    }

    @Test
    @DisplayName("app.auth.* beside the legacy settings refuses start")
    void besideLegacy() {
        Map<String, Object> both = with(engine(appAuth()), Map.of("app.security.auth.hmac-secret", OTHER));

        assertThatThrownBy(() -> ApplicationContext.run(both).close())
                .hasStackTraceContaining("configure one token source, not two");
    }

    @Test
    @DisplayName("an unknown app.auth key refuses start")
    void unknownKey() {
        Map<String, Object> typo = with(engine(appAuth()), Map.of("app.auth.claim.role", "r"));

        assertThatThrownBy(() -> ApplicationContext.run(typo).close()).hasStackTraceContaining("app.auth.claim.role");
    }

    @Test
    @DisplayName("hosted mode refuses app.auth.*")
    void hostedRefuses() {
        Map<String, Object> hosted = with(engine(appAuth()), Map.of(
                "app.security.auth.hmac-secret", OTHER, "app.security.multi-tenant.provisioning-url",
                "http://provisioning/api"));
        hosted.keySet().removeIf(key -> key.startsWith("spring.datasource."));

        assertThatThrownBy(() -> ApplicationContext.run(hosted).close()).hasStackTraceContaining("hosted mode");
    }

    @Test
    @DisplayName("a document with rules for a user role and no verifier starts, warns, and rejects every token")
    void noVerifierStarts(@TempDir Path dir) throws Exception {
        Logger log = (Logger) LoggerFactory.getLogger(AppConfig.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        log.addAppender(logs);
        try (ApplicationContext context = ApplicationContext.run(with(engine(Map.of()), withFile(file(dir, ""))))) {
            JwtService service = context.getBean(JwtService.class);

            String own = token(SECRET);

            assertThatThrownBy(() -> service.verify(own, "demo")).isInstanceOf(JwtVerificationException.class);
            assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.startsWith("no_token_verifier"));
        } finally {
            log.detachAppender(logs);
        }
    }
}
