package io.github.excalibase.admin;

import io.github.excalibase.support.EngineClient;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.FreePort;
import io.github.excalibase.support.HttpAnswer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

import static io.github.excalibase.admin.AdminTestEngine.DOCUMENT;
import static io.github.excalibase.admin.AdminTestEngine.HEADER;
import static io.github.excalibase.admin.AdminTestEngine.SECRET;
import static org.assertj.core.api.Assertions.assertThat;

/** The Auth page's two calls on a real engine: the redacted settings, and "test a token". */
class AuthEndpointIntegrationTest extends EngineIntegrationTest {

    private static final int ADMIN_PORT = FreePort.find();
    private static final Path FILE = AdminTestEngine.documentFile(DOCUMENT);
    private static final String AUTH = "/_admin/v1/auth";
    private static final String CHECK = "/_admin/v1/auth/check";
    private static final String REASON = "$.reason";

    private final EngineClient admin = AdminTestEngine.on(ADMIN_PORT);

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = AdminTestEngine.authPageOn(FILE, -1, ADMIN_PORT);
        return properties;
    }

    private HttpAnswer check(String token) {
        return admin.post(CHECK, token, HEADER, SECRET, "Content-Type", "text/plain");
    }

    @Test
    @DisplayName("GET /auth shows the engine's settings and the secret only as set")
    void settings() {
        HttpAnswer answer = admin.get(AUTH, HEADER, SECRET);

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.<String>jsonPath("$.source")).isEqualTo("engine");
        assertThat(answer.<String>jsonPath("$.secret")).isEqualTo("set");
        assertThat(answer.<String>jsonPath("$.issuer")).isEqualTo(AuthTokens.ISSUER);
        assertThat(answer.<String>jsonPath("$.documentJwt")).isEqualTo("absent");
        assertThat(answer.<String>jsonPath("$.claims.userId")).isEqualTo("sub");
        assertThat(answer.body()).doesNotContain(AuthTokens.SECRET);
    }

    @Test
    @DisplayName("both calls need the admin secret")
    void needsTheSecret() {
        assertThat(admin.get(AUTH).status()).isEqualTo(401);
        assertThat(admin.post(CHECK, "x.y.z").status()).isEqualTo(401);
        assertThat(http.get(AUTH, HEADER, SECRET).status()).isEqualTo(404);
        assertThat(http.post(CHECK, "x.y.z", HEADER, SECRET).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("a good token says who it is and which variables a rule can use")
    void goodToken() {
        HttpAnswer answer = check(AuthTokens.sign(c -> { }));

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.<Boolean>jsonPath("$.valid")).isTrue();
        assertThat(answer.<String>jsonPath("$.role")).isEqualTo("user");
        assertThat(answer.<String>jsonPath("$.userId")).isEqualTo("u-1");
        assertThat(answer.<String>jsonPath("$.sessionVariables['x-excalibase-user-id']")).isEqualTo("u-1");
    }

    @Test
    @DisplayName("each refusal is a 200 with its reason, and the token never comes back")
    void refusals() {
        String expired = AuthTokens.sign(c -> c.expirationTime(Date.from(Instant.now().minusSeconds(3600))));
        String audience = AuthTokens.sign(c -> c.audience("another-api"));
        String malformed = "canary-not-a-token";

        assertThat(check(expired).<String>jsonPath(REASON)).isEqualTo("expired");
        assertThat(check(audience).<String>jsonPath(REASON)).isEqualTo("audience");
        HttpAnswer answer = check(malformed);
        assertThat(answer.<Boolean>jsonPath("$.valid")).isFalse();
        assertThat(answer.<String>jsonPath(REASON)).isEqualTo("malformed");
        assertThat(answer.body()).doesNotContain(malformed).doesNotContain(expired);
    }

    @Test
    @DisplayName("a body over 8 KB is refused with 413 and is not echoed")
    void tooLarge() {
        String huge = "a".repeat(8 * 1024 + 1);

        HttpAnswer answer = check(huge);

        assertThat(answer.status()).isEqualTo(413);
        assertThat(answer.body()).doesNotContain("aaaa");
    }

    @Test
    @DisplayName("a JSON body is just text: a bad one does not leak through a binding error")
    void jsonBody() {
        HttpAnswer answer = admin.post(CHECK, "{\"token\": \"canary-json-token\"", HEADER, SECRET, "Content-Type",
                "application/json");

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.<String>jsonPath(REASON)).isEqualTo("malformed");
        assertThat(answer.body()).doesNotContain("canary-json-token");
    }
}
