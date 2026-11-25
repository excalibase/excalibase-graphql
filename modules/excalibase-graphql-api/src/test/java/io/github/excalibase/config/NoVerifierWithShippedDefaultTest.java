package io.github.excalibase.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.security.JwtService;
import io.github.excalibase.security.JwtVerificationException;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The upgrade case: a compose file written before the token settings moved (it only sets JWT_SECRET) beside the new
 * default permissions file starts, but nothing verifies a token, so every token is refused, and the log says so.
 * The new compose settings make the same file verify tokens.
 */
class NoVerifierWithShippedDefaultTest {

    private static final String SECRET = "change-me-to-a-long-random-secret-of-32-bytes-or-more";
    private static final String AUDIENCE = "my-app-api";

    private static Path shipped() {
        Path here = Path.of("").toAbsolutePath();
        while (here != null && !Files.isDirectory(here.resolve("deploy/standalone"))) {
            here = here.getParent();
        }
        return here.resolve("deploy/standalone/metadata/permissions.yaml");
    }

    private static Map<String, Object> engine(Path dir, Map<String, Object> extra) throws Exception {
        Path file = Files.copy(shipped(), dir.resolve("permissions.yaml"));
        Map<String, Object> properties = new HashMap<>(Map.of(
                "spring.datasource.url", "jdbc:postgresql://localhost:59999/nonexistent",
                "spring.datasource.username", "fake", "spring.datasource.password", "fake",
                "app.project-id", "my-app", "app.nats.enabled", "false",
                "micronaut.server.port", "-1", "endpoints.all.port", "-1",
                "metadata.source", "file", "metadata.file.path", file.toString()));
        properties.putAll(extra);
        return properties;
    }

    private static String token() throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder().subject("u-1")
                .audience(AUDIENCE).claim("role", "user").expirationTime(Date.from(Instant.now().plusSeconds(300))).build());
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    @Test
    @DisplayName("the old compose (JWT_SECRET only) with the new default file starts, warns, and rejects every token")
    void oldComposeStartsAndRejects(@TempDir Path dir) throws Exception {
        Logger log = (Logger) LoggerFactory.getLogger(AppConfig.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        log.addAppender(logs);
        String token = token();
        try (ApplicationContext context = ApplicationContext.run(engine(dir, Map.of("jwt.secret", SECRET)))) {
            JwtService service = context.getBean(JwtService.class);

            assertThatThrownBy(() -> service.verify(token, "my-app")).isInstanceOf(JwtVerificationException.class);
            assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.startsWith("no_token_verifier"));
        } finally {
            log.detachAppender(logs);
        }
    }

    @Test
    @DisplayName("the new compose settings (APP_AUTH_*) make the same file verify a token for the role it names")
    void newComposeVerifies(@TempDir Path dir) throws Exception {
        Map<String, Object> auth = Map.of("app.auth.secret", SECRET, "app.auth.audience", AUDIENCE);
        String token = token();
        try (ApplicationContext context = ApplicationContext.run(engine(dir, auth))) {
            JwtClaims claims = context.getBean(JwtService.class).verify(token, "my-app");

            assertThat(claims.userId()).isEqualTo("u-1");
            assertThat(claims.role()).isEqualTo("user");
        }
    }
}
