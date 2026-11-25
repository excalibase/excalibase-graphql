package io.github.excalibase.metadata;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.excalibase.schema.ReservedSchemas;
import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.security.JwtVerificationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * STRICT precedence: a document {@code jwt:} beside engine token settings is refused, except that an
 * {@code app.auth.*} section that says the same thing (every non-secret field, and the key bytes) is accepted so a
 * deployment can migrate without a gap. Legacy {@code app.security.auth.*} is always refused.
 */
class AuthPrecedenceTest {

    private static final String SECRET = AuthSettingsReaderTest.SECRET;
    private static final UnaryOperator<String> ENV = name -> "DOC_SECRET".equals(name) ? SECRET : null;
    private static final String LEGACY_REFUSAL = "jwt: the engine already has token settings of its own "
            + "(app.security.auth.*); keep them in one place, there or in the document";

    private static EngineAuth engine(Map<String, String> overrides) {
        Map<String, String> env = new HashMap<>(Map.of("APP_AUTH_SECRET", SECRET, "APP_AUTH_AUDIENCE", "my-api"));
        env.putAll(overrides);
        return AuthSettingsReader.fromEnv(env);
    }

    private static String document(String jwt) {
        return "format: 1\nprojectId: demo\n" + (jwt.isEmpty() ? "" : "jwt:\n" + jwt.indent(2))
                + "tables:\n  - table: public.orders\n    role: user\n    select:\n      filter: {}\n      columns: \"*\"\n";
    }

    private static final String SAME = "secret: env:DOC_SECRET\naudience: my-api\n";

    private static MetadataDocumentParser parser(EngineAuth auth) {
        return new MetadataDocumentParser(ReservedSchemas.fromConfig(""), "demo", auth);
    }

    private static void refused(EngineAuth auth, String jwt) {
        MetadataDocumentParser parser = parser(auth);
        String text = document(jwt);

        assertThatThrownBy(() -> parser.check(text, 1, ENV)).isInstanceOf(MetadataDocumentException.class)
                .hasMessageContaining("app.auth.*").hasMessageNotContaining(SECRET);
    }

    @Test
    void legacySettingsAndADocumentJwt_areAlwaysRefused_withTheMessageUnchanged() {
        MetadataDocumentParser legacy = parser(EngineAuth.of(true));
        MetadataDocumentParser legacyFlag = new MetadataDocumentParser(ReservedSchemas.fromConfig(""), "demo", true);
        String text = document(SAME);

        assertThatThrownBy(() -> legacy.check(text, 1, ENV))
                .isInstanceOf(MetadataDocumentException.class).hasMessage(LEGACY_REFUSAL);
        // even a section that would be identical to app.auth is refused when the engine's settings are the legacy ones
        assertThatThrownBy(() -> legacyFlag.parse(text, 1)).hasMessage(LEGACY_REFUSAL);
    }

    @Test
    void anIdenticalSection_isAccepted() {
        assertThat(parser(engine(Map.of())).check(document(SAME), 1, ENV).tables()).hasSize(1);
    }

    @Test
    void anIdenticalSection_buildsOnlyTheEngineVerifier_andFetchesNothing() {
        EngineAuth auth = AuthSettingsReader.fromEnv(Map.of("APP_AUTH_ISSUER", "https://idp.invalid/",
                "APP_AUTH_JWKS_URL", "https://idp.invalid/jwks.json", "APP_AUTH_AUDIENCE", "my-api"));
        String same = "issuer: https://idp.invalid/\njwksUrl: https://idp.invalid/jwks.json\naudience: my-api\n";

        // the host cannot be reached: a document verifier would try to fetch keys and fail the load
        MetadataDocument loaded = parser(auth).load(document(same), 1);

        assertThat(loaded.verifier()).isNull();
        assertThat(loaded.permissions().tables()).hasSize(1);
    }

    @Test
    void everyDifferingNonSecretField_isRefused() {
        refused(engine(Map.of()), "secret: env:DOC_SECRET\naudience: other-api\n");
        refused(engine(Map.of()), SAME + "algorithms: [HS512]\n");
        refused(engine(Map.of()), SAME + "clockSkew: 5\n");
        refused(engine(Map.of()), SAME + "claims:\n  role: groups\n");
        refused(engine(Map.of()), SAME + "claims:\n  userId: uid\n");
        refused(engine(Map.of()), SAME + "claims:\n  defaultRole: viewer\n");
        refused(engine(Map.of()), SAME + "claims:\n  extra:\n    org-id: org\n");
        refused(AuthSettingsReader.fromEnv(Map.of("APP_AUTH_ISSUER", "https://idp.example.com/",
                "APP_AUTH_JWKS_URL", "https://idp.example.com/jwks.json", "APP_AUTH_AUDIENCE", "my-api")),
                "issuer: https://other.example.com/\njwksUrl: https://idp.example.com/jwks.json\naudience: my-api\n");
        refused(AuthSettingsReader.fromEnv(Map.of("APP_AUTH_ISSUER", "https://idp.example.com/",
                "APP_AUTH_JWKS_URL", "https://idp.example.com/jwks.json", "APP_AUTH_AUDIENCE", "my-api")),
                "issuer: https://idp.example.com/\njwksUrl: https://idp.example.com/other.json\naudience: my-api\n");
    }

    @Test
    void aKeySourceOfAnotherKind_isRefused() {
        refused(engine(Map.of()), """
                issuer: https://idp.example.com/
                jwksUrl: https://idp.example.com/jwks.json
                audience: my-api
                """);
    }

    @Test
    void secretsAreComparedByTheirBytes_oneDifferentByteIsRefused_withoutNamingThem() {
        UnaryOperator<String> other = name -> SECRET.substring(0, SECRET.length() - 1) + "?";
        MetadataDocumentParser parser = parser(engine(Map.of()));
        String text = document(SAME);

        assertThatThrownBy(() -> parser.check(text, 1, other))
                .isInstanceOf(MetadataDocumentException.class).hasMessageContaining("app.auth.*")
                .hasMessageNotContaining(SECRET).hasMessageNotContaining("32");
    }

    @Test
    void aSecretThatCannotBeResolved_cannotBeProvedIdentical_andIsRefused() {
        MetadataDocumentParser parser = parser(engine(Map.of()));
        String text = document(SAME);

        assertThatThrownBy(() -> parser.check(text, 1, name -> null))
                .isInstanceOf(MetadataDocumentException.class).hasMessageContaining("app.auth.*");
    }

    @Test
    void theListsAreComparedAsSets_andTheKeyFileIsComparedByContent() {
        EngineAuth auth = engine(Map.of("APP_AUTH_ALGORITHMS", "HS512,HS256", "APP_AUTH_AUDIENCE", "b,a"));

        String section = """
                secret: env:DOC_SECRET
                algorithms: [HS256, HS512]
                audience: [a, b]
                """;

        assertThat(parser(auth).check(document(section), 1, ENV).tables()).hasSize(1);
    }

    @Test
    void withNoDocumentSection_theEngineSettingsStandAlone() {
        assertThat(parser(engine(Map.of())).check(document(""), 1, ENV).tables()).hasSize(1);
        assertThat(parser(engine(Map.of())).load(document(""), 1).verifier()).isNull();
    }

    @Test
    void theEngineVerifier_acceptsATokenForItsKey_andRefusesOneForTheDocumentsOldKey() throws Exception {
        EngineAuth auth = engine(Map.of());
        var verifier = auth.verifier();

        JwtClaims claims = verifier.verify(token(SECRET, "u-1"), "demo");
        assertThat(claims.userId()).isEqualTo("u-1");
        String foreign = token("a-different-secret-of-32-bytes-or-more!!", "u-1");

        assertThatThrownBy(() -> verifier.verify(foreign, "demo"))
                .isInstanceOf(JwtVerificationException.class);
    }

    static String token(String secret, String subject) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder().subject(subject)
                .audience("my-api").claim("role", "user").expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build());
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
