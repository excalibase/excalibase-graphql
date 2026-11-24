package io.github.excalibase.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.excalibase.metadata.AuthSettingsReader;
import io.github.excalibase.metadata.EngineAuth;
import io.github.excalibase.security.JwtService;
import io.github.excalibase.security.JwtVerificationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The hosted platform verifies tokens with its legacy settings and an audience bound to the project. Adding the
 * engine's own settings changed {@code AppConfig.jwtService}, which hosted uses: it must still build the same service.
 */
class HostedWiringUnchangedTest {

    private static final String SECRET = "test-hmac-secret-0123456789abcdef0123456789";

    private static String token(String audience) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().subject("u-1").claim("role", "user")
                .claim("project_id", "proj-1").expirationTime(Date.from(Instant.now().plusSeconds(300)));
        if (audience != null) {
            claims.audience(audience);
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private static JwtService hostedService() {
        SecurityProperties props = new SecurityProperties(true,
                new SecurityProperties.Auth(null, SECRET, null, null, null, null),
                new SecurityProperties.MultiTenant("http://provisioning/api", "", ""));
        return new AppConfig().jwtService(props, 30, null, EngineAuth.of(true));
    }

    @Test
    @DisplayName("hosted with the legacy settings: audience bound to the project, no document or engine verifier")
    void hostedServiceIsTheLegacyOne() throws Exception {
        JwtService service = hostedService();

        assertThat(service.verify(token("excalibase:proj-1"), "proj-1").userId()).isEqualTo("u-1");
        assertThatThrownBy(() -> service.verify(token("excalibase:proj-2"), "proj-1"))
                .isInstanceOf(JwtVerificationException.class);
        assertThatThrownBy(() -> service.verify(token(null), "proj-1")).isInstanceOf(JwtVerificationException.class);
    }

    @Test
    @DisplayName("the three-argument form the existing tests use is the same as with no engine settings")
    void threeArgumentFormIsUnchanged() throws Exception {
        SecurityProperties props = new SecurityProperties(true,
                new SecurityProperties.Auth(null, SECRET, null, null, null, null), null);

        assertThat(new AppConfig().jwtService(props, 30, null).verify(token("excalibase:proj-1"), "proj-1").userId())
                .isEqualTo("u-1");
    }

    @Test
    @DisplayName("hosted refuses any stray APP_AUTH_ name, with the exact message")
    void strayAppAuthNameInHostedMode() {
        assertThatThrownBy(() -> AuthSettingsReader.resolve(new AuthSettingsReader.Inputs(true, true, true,
                Set.of("APP_AUTH_STRAY"), Set.of(), key -> null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("app.auth.* (APP_AUTH_*) is refused in hosted mode (app.security.rls.policy-url or "
                        + "app.security.multi-tenant.provisioning-url is set): one engine-wide verifier would "
                        + "bypass the per-project audience check");
    }
}
