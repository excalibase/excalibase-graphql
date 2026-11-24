package io.github.excalibase.metadata;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The engine's own token settings: read from the {@code APP_AUTH_*} names, validated by the document's grammar. */
class AuthSettingsReaderTest {

    static final String SECRET = "an-hmac-secret-of-at-least-32-bytes!!";

    private static Map<String, String> hmac() {
        Map<String, String> env = new HashMap<>();
        env.put("APP_AUTH_SECRET", SECRET);
        env.put("APP_AUTH_AUDIENCE", "my-api");
        return env;
    }

    @Test
    void noAppAuthName_isNoEngineSettings() {
        EngineAuth auth = AuthSettingsReader.fromEnv(Map.of("PATH", "/bin"));

        assertThat(auth.engine()).isFalse();
        assertThat(auth.configured()).isFalse();
    }

    @Test
    void anHmacSecretAndAnAudience_makeEngineSettingsWithTheDocumentsDefaults() {
        JwtSettings settings = AuthSettingsReader.fromEnv(hmac()).settings();

        assertThat(settings.algorithms()).containsExactly("HS256");
        assertThat(settings.audience()).containsExactly("my-api");
        assertThat(settings.claims().role()).isEqualTo("role");
        assertThat(settings.claims().userId()).isEqualTo("sub");
        assertThat(settings.claims().extra()).isEmpty();
    }

    @Test
    void everyField_isReadFromItsName() {
        Map<String, String> env = hmac();
        env.put("APP_AUTH_ALGORITHMS", "HS384, HS512");
        env.put("APP_AUTH_AUDIENCE", "a,b");
        env.put("APP_AUTH_CLOCK_SKEW_SECONDS", "12");
        env.put("APP_AUTH_CLAIMS_ROLE", "https://x/role");
        env.put("APP_AUTH_CLAIMS_DEFAULT_ROLE", "user");
        env.put("APP_AUTH_CLAIMS_USER_ID", "uid");
        env.put("APP_AUTH_CLAIMS_EXTRA", "org-id=https://x/org, team=team");

        JwtSettings settings = AuthSettingsReader.fromEnv(env).settings();

        assertThat(settings.algorithms()).containsExactly("HS384", "HS512");
        assertThat(settings.audience()).containsExactly("a", "b");
        assertThat(settings.clockSkew().toSeconds()).isEqualTo(12);
        assertThat(settings.claims().role()).isEqualTo("https://x/role");
        assertThat(settings.claims().defaultRole()).isEqualTo("user");
        assertThat(settings.claims().userId()).isEqualTo("uid");
        assertThat(settings.claims().extra()).containsEntry("org-id", "https://x/org").containsEntry("team", "team");
    }

    @Test
    void aKeySetWithAnIssuer_isEngineSettingsWithoutASecret() {
        EngineAuth auth = AuthSettingsReader.fromEnv(Map.of("APP_AUTH_ISSUER", "https://idp.example.com/",
                "APP_AUTH_JWKS_URL", "https://idp.example.com/jwks.json", "APP_AUTH_AUDIENCE", "api"));

        assertThat(auth.settings().jwksUrl()).isEqualTo("https://idp.example.com/jwks.json");
        assertThat(auth.settings().secretRef()).isNull();
    }

    @Test
    void aSecretShorterThan32Bytes_isRefusedWithoutEchoingIt() {
        Map<String, String> env = hmac();
        env.put("APP_AUTH_SECRET", "short-secret");

        assertThatThrownBy(() -> AuthSettingsReader.fromEnv(env)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32").hasMessageNotContaining("short-secret");
    }

    @Test
    void aSecretAndASecretFile_areRefused() {
        Map<String, String> env = hmac();
        env.put("APP_AUTH_SECRET_FILE", "/run/secrets/jwt");

        assertThatThrownBy(() -> AuthSettingsReader.fromEnv(env)).hasMessageContaining("APP_AUTH_SECRET")
                .hasMessageContaining("APP_AUTH_SECRET_FILE").hasMessageContaining("not both");
    }

    @Test
    void aSecretFile_isReadForItsBytes(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("jwt");
        Files.writeString(file, SECRET + "\n");

        EngineAuth auth = AuthSettingsReader.fromEnv(Map.of("APP_AUTH_SECRET_FILE", file.toString(),
                "APP_AUTH_AUDIENCE", "my-api"));

        assertThat(auth.engine()).isTrue();
        assertThat(auth.identicalSecret(SECRET.getBytes(StandardCharsets.UTF_8))).isTrue();
    }

    @Test
    void theDocumentsGrammarAppliesToEveryField() {
        Map<String, String> noAudience = new HashMap<>(Map.of("APP_AUTH_SECRET", SECRET));
        assertThatThrownBy(() -> AuthSettingsReader.fromEnv(noAudience)).hasMessageContaining("audience");

        Map<String, String> badAlgorithm = hmac();
        badAlgorithm.put("APP_AUTH_ALGORITHMS", "RS256");
        assertThatThrownBy(() -> AuthSettingsReader.fromEnv(badAlgorithm)).hasMessageContaining("algorithms");

        Map<String, String> badSkew = hmac();
        badSkew.put("APP_AUTH_CLOCK_SKEW_SECONDS", "9000");
        assertThatThrownBy(() -> AuthSettingsReader.fromEnv(badSkew)).hasMessageContaining("clockSkew");

        Map<String, String> notANumber = hmac();
        notANumber.put("APP_AUTH_CLOCK_SKEW_SECONDS", "soon");
        assertThatThrownBy(() -> AuthSettingsReader.fromEnv(notANumber)).hasMessageContaining("clockSkew");

        Map<String, String> badExtra = hmac();
        badExtra.put("APP_AUTH_CLAIMS_EXTRA", "no-equals-sign");
        assertThatThrownBy(() -> AuthSettingsReader.fromEnv(badExtra)).hasMessageContaining("extra");

        Map<String, String> reservedExtra = hmac();
        reservedExtra.put("APP_AUTH_CLAIMS_EXTRA", "user=org");
        assertThatThrownBy(() -> AuthSettingsReader.fromEnv(reservedExtra)).hasMessageContaining("extra.user");
    }

    @Test
    void aSettingWithNoKeySource_isRefused() {
        assertThatThrownBy(() -> AuthSettingsReader.fromEnv(Map.of("APP_AUTH_AUDIENCE", "api")))
                .hasMessageContaining("needs jwksUrl");
    }

    @Test
    void aBlankValue_countsAsNotSet() {
        Map<String, String> env = new HashMap<>(Map.of("APP_AUTH_SECRET", " ", "APP_AUTH_AUDIENCE", ""));

        assertThat(AuthSettingsReader.fromEnv(env).engine()).isFalse();
    }

    @Test
    void theAllowListIsTheNamedSettingsOnly() {
        assertThat(AuthSettingsReader.ENV_NAMES).containsExactlyInAnyOrder("APP_AUTH_ISSUER", "APP_AUTH_JWKS_URL",
                "APP_AUTH_SECRET", "APP_AUTH_SECRET_FILE", "APP_AUTH_ALGORITHMS", "APP_AUTH_AUDIENCE",
                "APP_AUTH_CLOCK_SKEW_SECONDS", "APP_AUTH_CLAIMS_ROLE", "APP_AUTH_CLAIMS_DEFAULT_ROLE",
                "APP_AUTH_CLAIMS_USER_ID", "APP_AUTH_CLAIMS_EXTRA");
        assertThat(List.of("APP_AUTH_CLAIM_ROLE")).doesNotContainAnyElementsOf(AuthSettingsReader.ENV_NAMES);
    }
}
