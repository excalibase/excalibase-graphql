package io.github.excalibase.metadata;

import io.github.excalibase.permissions.PermissionSet;
import io.github.excalibase.schema.ReservedSchemas;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/** What the Auth page shows: where the token settings come from, never the secret, and how the document's section stands. */
class AuthReportTest {

    private static final String SECRET = AuthSettingsReaderTest.SECRET;
    private static final String DOC_SECRET_VALUE = "a-document-secret-of-at-least-32-bytes!!";
    private static final UnaryOperator<String> ENV = name -> "DOC_SECRET".equals(name) ? DOC_SECRET_VALUE : SECRET;
    private static final String RULES = """
            tables:
              - table: public.orders
                role: user
                select:
                  filter: {}
                  columns: "*"
            """;
    private static final String ANON_ONLY = RULES.replace("role: user", "role: anon");
    private static final String HEADER = "format: 1\nprojectId: demo\n";
    private static final String DOC_JWT = "jwt:\n  secret: file:" + secretFile() + "\n  audience: my-api\n";
    private static final String SOURCE = "source";
    private static final String API = "my-api";
    private static final String JWKS_URL = "jwksUrl";
    private static final String ABSENT = "absent";
    private static final String UNSET = "unset";
    private static final String KEYS_LOADED = "keysLoaded";
    private static final String AUDIENCE_NAME = "APP_AUTH_AUDIENCE";
    private static final String IDP = "https://idp.example.com/";
    private static final String DOCUMENT_JWT = "documentJwt";
    private static final String SECRET_KEY = "secret";

    private static Path secretFile() {
        try {
            return Files.writeString(Files.createTempFile("doc-secret", ""), DOC_SECRET_VALUE);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static EngineAuth engine() {
        return AuthSettingsReader.fromEnv(Map.of("APP_AUTH_SECRET", SECRET, AUDIENCE_NAME, API,
                "APP_AUTH_ISSUER", IDP, "APP_AUTH_CLAIMS_EXTRA", "org=app.org"));
    }

    private static MetadataSource.Snapshot served(String text, EngineAuth auth) {
        MetadataDocumentParser parser = new MetadataDocumentParser(ReservedSchemas.fromConfig(""), "demo", auth);
        PermissionSet permissions = parser.parse(text, 1);
        MetadataDocument loaded = parser.load(text, 1);
        return new MetadataSource.Snapshot(permissions, 1, "v1", Instant.now(), loaded.verifier(), text);
    }

    private static Map<String, Object> report(EngineAuth auth, String body) {
        return AuthReport.of(auth, null, served(HEADER + body, auth), ENV);
    }

    private static List<String> codes(Map<String, Object> report) {
        @SuppressWarnings("unchecked")
        List<Map<String, String>> warnings = (List<Map<String, String>>) report.get("warnings");
        return warnings.stream().map(warning -> warning.get("code")).toList();
    }

    @Test
    @DisplayName("engine settings: the source, the values, the claims, and the secret only as set")
    void engineSettings() {
        Map<String, Object> report = report(engine(), RULES);

        assertThat(report).containsEntry(SOURCE, "engine").containsEntry(SECRET_KEY, "set")
                .containsEntry(KEYS_LOADED, true).containsEntry("issuer", IDP)
                .containsEntry("algorithms", List.of("HS256")).containsEntry("audience", List.of(API))
                .containsEntry("clockSkew", 30L).containsEntry(DOCUMENT_JWT, ABSENT);
        assertThat(report.get(JWKS_URL)).isNull();
        assertThat(report.get("claims")).isEqualTo(Map.of("role", "role", "defaultRole", "", "userId", "sub",
                "extra", Map.of("org", "app.org")));
        assertThat(report.toString()).doesNotContain(SECRET);
        assertThat(codes(report)).isEmpty();
    }

    @Test
    @DisplayName("a key set shows its address without credentials, a query or a fragment, and no secret")
    void keySet() {
        EngineAuth auth = AuthSettingsReader.fromEnv(Map.of("APP_AUTH_ISSUER", IDP,
                "APP_AUTH_JWKS_URL", "https://idp.example.com/jwks.json?key=abc#frag", AUDIENCE_NAME, "api"));

        Map<String, Object> report = AuthReport.of(auth, null, null, ENV);

        assertThat(report).containsEntry(JWKS_URL, "https://idp.example.com/jwks.json").containsEntry(SECRET_KEY, UNSET);
        assertThat(report.toString()).doesNotContain("abc");
    }

    @Test
    @DisplayName("an identical document section is reported as identical, with the note to remove it")
    void identical() {
        Map<String, String> same = Map.of("APP_AUTH_SECRET", DOC_SECRET_VALUE, AUDIENCE_NAME, API);
        EngineAuth auth = AuthSettingsReader.fromEnv(same);

        Map<String, Object> report = AuthReport.of(auth, null, served(HEADER + DOC_JWT + RULES, auth), ENV);

        assertThat(report).containsEntry(SOURCE, "engine").containsEntry(DOCUMENT_JWT, "identical");
        assertThat(codes(report)).containsExactly("jwt_identical_remove_it");
    }

    @Test
    @DisplayName("a document section that differs from the engine's is reported as different, without saying how")
    void different() {
        EngineAuth auth = engine();
        MetadataSource.Snapshot snapshot = new MetadataSource.Snapshot(new PermissionSet("demo", 1, List.of(), List.of(), List.of()), 1, "v1",
                Instant.now(), null, HEADER + DOC_JWT + RULES);

        Map<String, Object> report = AuthReport.of(auth, null, snapshot, ENV);

        assertThat(report).containsEntry(DOCUMENT_JWT, "different");
        assertThat(report.toString()).doesNotContain(DOC_SECRET_VALUE);
    }

    @Test
    @DisplayName("settings only in the document are reported as the document's, deprecated, with no secret")
    void documentOnly() {
        Map<String, Object> report = report(EngineAuth.NONE, DOC_JWT + RULES);

        assertThat(report).containsEntry(SOURCE, "document").containsEntry(SECRET_KEY, "set")
                .containsEntry(KEYS_LOADED, true).containsEntry("audience", List.of(API))
                .containsEntry(DOCUMENT_JWT, "present-deprecated");
        assertThat(codes(report)).containsExactly("jwt_deprecated");
        assertThat(report.toString()).doesNotContain("doc-secret").doesNotContain(DOC_SECRET_VALUE);
    }

    @Test
    @DisplayName("nothing verifies a token: source none, and the note when a role other than anon has rules")
    void nothing() {
        Map<String, Object> withRules = report(EngineAuth.NONE, RULES);
        Map<String, Object> anonOnly = report(EngineAuth.NONE, ANON_ONLY);

        assertThat(withRules).containsEntry(SOURCE, "none").containsEntry(KEYS_LOADED, false)
                .containsEntry(SECRET_KEY, UNSET).containsEntry(DOCUMENT_JWT, ABSENT);
        assertThat(codes(withRules)).containsExactly("no_token_verifier");
        assertThat(codes(anonOnly)).isEmpty();
    }

    @Test
    @DisplayName("no document served yet: nothing about it, and no verifier note")
    void noDocument() {
        Map<String, Object> report = AuthReport.of(EngineAuth.NONE, null, null, ENV);

        assertThat(report).containsEntry(SOURCE, "none").containsEntry(DOCUMENT_JWT, ABSENT);
        assertThat(codes(report)).isEmpty();
    }

    @Test
    @DisplayName("the legacy settings: reported as legacy-engine, secret set, keys not known here")
    void legacy() {
        AuthReport.Legacy hmac = new AuthReport.Legacy("", "https://issuer.example", true);
        AuthReport.Legacy jwks = new AuthReport.Legacy("https://idp.example.com/jwks?token=zzz", "", false);

        Map<String, Object> viaHmac = AuthReport.of(EngineAuth.of(true), hmac, null, ENV);
        Map<String, Object> viaJwks = AuthReport.of(EngineAuth.of(true), jwks, null, ENV);

        assertThat(viaHmac).containsEntry(SOURCE, "legacy-engine").containsEntry(SECRET_KEY, "set")
                .containsEntry("issuer", "https://issuer.example");
        assertThat(viaHmac.get(KEYS_LOADED)).isNull();
        assertThat(viaJwks).containsEntry(JWKS_URL, "https://idp.example.com/jwks").containsEntry(SECRET_KEY, UNSET);
        assertThat(viaJwks.toString()).doesNotContain("zzz");
    }
}
