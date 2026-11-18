package io.github.excalibase.metadata;

import io.github.excalibase.schema.ReservedSchemas;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static io.github.excalibase.metadata.MetadataFixtures.PROJECT;
import static io.github.excalibase.metadata.MetadataFixtures.WITH_JWT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The {@code jwt:} section is validated by the parser, so the engine and {@code excalibase validate} agree. */
class JwtSettingsParserTest {

    private final MetadataDocumentParser parser = new MetadataDocumentParser(ReservedSchemas.fromConfig(""), PROJECT);

    private static String document(String jwt) {
        return "format: 1\nprojectId: demo\njwt:\n" + jwt.indent(2) + "tables: []\n";
    }

    @Test
    void aJwtSection_isNoLongerRefused() {
        assertThat(parser.parse(WITH_JWT, 1).tables()).isEmpty();
    }

    @Test
    void aJwtSection_isRefused_whenTheEngineHasItsOwnJwtSettings() {
        MetadataDocumentParser withEnv = new MetadataDocumentParser(ReservedSchemas.fromConfig(""), PROJECT, true);

        assertThatThrownBy(() -> withEnv.parse(WITH_JWT, 1)).isInstanceOf(MetadataDocumentException.class)
                .hasMessageContaining("jwt").hasMessageContaining("one place");
        assertThat(withEnv.parse(MetadataFixtures.GOOD_YAML, 1)).isNotNull();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "no audience|issuer: https://i/\\njwksUrl: https://i/jwks|jwt.audience",
            "empty audience list|issuer: https://i/\\njwksUrl: https://i/jwks\\naudience: []|jwt.audience",
            "jwks without issuer|jwksUrl: https://i/jwks\\naudience: a|jwt.issuer",
            "no key source|audience: a|jwt: needs",
            "literal secret|secret: hunter2hunter2hunter2hunter2hunter2\\naudience: a|reference",
            "secret and jwks|secret: env:S\\njwksUrl: https://i/jwks\\nissuer: https://i/\\naudience: a|jwt.secret",
            "alg none|issuer: https://i/\\njwksUrl: https://i/jwks\\naudience: a\\nalgorithms: [none]|jwt.algorithms",
            "hmac with jwks|issuer: https://i/\\njwksUrl: https://i/jwks\\naudience: a\\nalgorithms: [HS256]|jwt.algorithms",
            "rsa with secret|secret: env:S\\naudience: a\\nalgorithms: [RS256]|jwt.algorithms",
            "empty algorithms|issuer: https://i/\\njwksUrl: https://i/jwks\\naudience: a\\nalgorithms: []|jwt.algorithms",
            "plain http outside|issuer: https://i/\\njwksUrl: http://idp.example.com/jwks\\naudience: a|https",
            "credentials in url|issuer: https://i/\\njwksUrl: https://u:p@i/jwks\\naudience: a|https",
            "unknown key|issuer: https://i/\\njwksUrl: https://i/jwks\\naudience: a\\nsurprise: 1|jwt.surprise",
            "upper-case extra|issuer: https://i/\\njwksUrl: https://i/jwks\\naudience: a\\nclaims:\\n  extra:\\n    Org: o|jwt.claims.extra",
            "reserved extra|issuer: https://i/\\njwksUrl: https://i/jwks\\naudience: a\\nclaims:\\n  extra:\\n    user-id: o|jwt.claims.extra",
            "service default role|issuer: https://i/\\njwksUrl: https://i/jwks\\naudience: a\\nclaims:\\n  defaultRole: service|jwt.claims.defaultRole",
            "negative skew|issuer: https://i/\\njwksUrl: https://i/jwks\\naudience: a\\nclockSkew: -1|jwt.clockSkew",
            "huge skew|issuer: https://i/\\njwksUrl: https://i/jwks\\naudience: a\\nclockSkew: 99999|jwt.clockSkew",
            "blank path|issuer: https://i/\\njwksUrl: https://i/jwks\\naudience: a\\nclaims:\\n  role: \" \"|jwt.claims.role"})
    void aBadJwtSection_isRefused_naming_thePath(String name, String jwt, String path) {
        String text = document(jwt.replace("\\n", "\n"));

        assertThatThrownBy(() -> parser.parse(text, 1))
                .isInstanceOf(MetadataDocumentException.class)
                .hasMessageContaining(path);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "https jwks|issuer: https://i/\\njwksUrl: https://i/jwks\\naudience: a",
            "loopback http|issuer: http://127.0.0.1:9/\\njwksUrl: http://127.0.0.1:9/jwks\\naudience: a",
            "cluster http|issuer: http://idp.auth.svc/\\njwksUrl: http://idp.auth.svc/jwks\\naudience: a",
            "discovery by issuer|issuer: https://i/\\naudience: [a, b]",
            "secret from env|secret: env:EXC_SECRET\\naudience: a",
            "secret from file with issuer|secret: file:/run/secrets/jwt\\nissuer: me\\naudience: a",
            "everything|issuer: https://i/\\njwksUrl: https://i/jwks\\naudience: a\\nalgorithms: [RS256, ES256, PS256]\\nclockSkew: 60\\nclaims:\\n  role: roles\\n  defaultRole: user\\n  userId: sub\\n  extra:\\n    org-id: https://x/org"})
    void aGoodJwtSection_isAccepted(String name, String jwt) {
        assertThat(parser.parse(document(jwt.replace("\\n", "\n")), 1)).isNotNull();
    }
}
