package io.github.excalibase.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.excalibase.metadata.MetadataDocumentParser;
import io.github.excalibase.metadata.MetadataSource;
import io.github.excalibase.permissions.PermissionProvider;
import io.github.excalibase.security.JwtService;
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
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Moving a compose file or manifest from the document's {@code jwt:} to {@code APP_AUTH_*}, in the order the docs give:
 * add the engine settings and keep the old secret, restart and see {@code metadata_jwt_identical}, remove {@code jwt:}
 * from the document, and only then drop the old secret. Swapping the secret for the new name at once, with the old
 * document, is refused and serves nothing.
 */
class UpgradeOrderTest {

    private static final String SECRET = "change-me-to-a-long-random-secret-of-32-bytes-or-more";
    private static final String AUDIENCE = "my-app-api";
    private static final String RULES = """
            tables:
              - table: public.todos
                role: user
                select:
                  filter: {}
                  columns: "*"
            """;
    private static final String HEADER = "format: 1\nprojectId: my-app\n";

    private static String jwt(String secretReference) {
        return "jwt:\n  secret: " + secretReference + "\n  algorithms: [HS256]\n  audience: " + AUDIENCE + "\n";
    }

    private static Map<String, Object> engine(Path file, Map<String, Object> extra) {
        Map<String, Object> properties = new HashMap<>(Map.of(
                "spring.datasource.url", "jdbc:postgresql://localhost:59999/nonexistent",
                "spring.datasource.username", "fake", "spring.datasource.password", "fake",
                "app.project-id", "my-app", "app.nats.enabled", "false",
                "micronaut.server.port", "-1", "endpoints.all.port", "-1",
                "metadata.source", "file", "metadata.file.path", file.toString(),
                "metadata.file.poll-interval-ms", "50"));
        properties.putAll(extra);
        return properties;
    }

    private static String token() throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder().subject("u-1")
                .audience(AUDIENCE).claim("role", "user").expirationTime(Date.from(Instant.now().plusSeconds(300))).build());
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private static void awaitServed(MetadataSource source, Predicate<String> text) throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            if (source.current().map(snapshot -> text.test(snapshot.text())).orElse(false)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the document was not served in time");
    }

    @Test
    @DisplayName("swapping JWT_SECRET for APP_AUTH_SECRET at once, with the old document, serves nothing")
    void swapAtOnceIsRefused(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("permissions.yaml"), HEADER + jwt("env:JWT_SECRET") + RULES);
        Map<String, Object> auth = Map.of("app.auth.secret", SECRET, "app.auth.audience", AUDIENCE);

        try (ApplicationContext context = ApplicationContext.run(engine(file, auth))) {
            MetadataSource source = (MetadataSource) context.getBean(PermissionProvider.class);

            assertThat(source.current()).isEmpty();
            assertThat(source.status().lastRefusal()).isEqualTo("invalid_document");
        }
    }

    @Test
    @DisplayName("add APP_AUTH_*, keep the old secret, see metadata_jwt_identical, remove jwt:, and tokens verify throughout")
    void theOrderInTheDocs(@TempDir Path dir) throws Exception {
        Path old = Files.writeString(dir.resolve("old-secret"), SECRET);
        Path file = Files.writeString(dir.resolve("permissions.yaml"), HEADER + jwt("file:" + old) + RULES);
        Logger log = (Logger) LoggerFactory.getLogger(MetadataDocumentParser.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        log.addAppender(logs);
        String token = token();
        try {
            try (ApplicationContext before = ApplicationContext.run(engine(file, Map.of()))) {
                assertThat(before.getBean(JwtService.class).verify(token, "my-app").userId()).isEqualTo("u-1");
            }
            Map<String, Object> auth = Map.of("app.auth.secret", SECRET, "app.auth.audience", AUDIENCE);
            try (ApplicationContext after = ApplicationContext.run(engine(file, auth))) {
                MetadataSource source = (MetadataSource) after.getBean(PermissionProvider.class);
                JwtService service = after.getBean(JwtService.class);

                assertThat(source.current()).isPresent();
                assertThat(service.verify(token, "my-app").userId()).isEqualTo("u-1");
                assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                        .anyMatch(message -> message.startsWith("metadata_jwt_identical"));

                Files.writeString(file, HEADER + RULES);
                awaitServed(source, text -> !text.contains("jwt:"));

                assertThat(service.verify(token, "my-app").userId()).isEqualTo("u-1");
            }
        } finally {
            log.detachAppender(logs);
        }
    }
}
