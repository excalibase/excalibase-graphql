package io.github.excalibase.admin;

import com.nimbusds.jose.JWSAlgorithm;
import io.github.excalibase.metadata.AuthSettingsReader;
import io.github.excalibase.security.JwtVerificationException;
import io.github.excalibase.security.TokenVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.github.excalibase.admin.AuthTokens.AUDIENCE;
import static io.github.excalibase.admin.AuthTokens.ISSUER;
import static io.github.excalibase.admin.AuthTokens.OTHER_SECRET;
import static io.github.excalibase.admin.AuthTokens.SECRET;
import static org.assertj.core.api.Assertions.assertThat;

/** Every refusal a token can meet, from a real token and the real verifier, comes back as one name and nothing else. */
class TokenCheckTest {

    private static final String REASON = "reason";
    private static final TokenVerifier VERIFIER = AuthSettingsReader.fromEnv(Map.of(
            "APP_AUTH_SECRET", SECRET, "APP_AUTH_AUDIENCE", AUDIENCE, "APP_AUTH_ISSUER", ISSUER)).verifier();

    private static Map<String, Object> check(String token) {
        return TokenCheck.run(VERIFIER, token.getBytes(StandardCharsets.UTF_8), "proj");
    }

    static Stream<Arguments> refusals() {
        long past = Instant.now().minusSeconds(3600).toEpochMilli();
        long future = Instant.now().plusSeconds(3600).toEpochMilli();
        return Stream.of(
                Arguments.of("expired", AuthTokens.sign(c -> c.expirationTime(new Date(past)))),
                Arguments.of("not_yet_valid", AuthTokens.sign(c -> c.notBeforeTime(new Date(future)))),
                Arguments.of("bad_signature", AuthTokens.sign(AuthTokens.good(), JWSAlgorithm.HS256, OTHER_SECRET)),
                Arguments.of("algorithm", AuthTokens.unsigned()),
                Arguments.of("algorithm", AuthTokens.sign(AuthTokens.good(), JWSAlgorithm.HS512, SECRET.repeat(2))),
                Arguments.of("issuer", AuthTokens.sign(c -> c.issuer("https://elsewhere.example/"))),
                Arguments.of("audience", AuthTokens.sign(c -> c.audience("another-api"))),
                Arguments.of("role_claim", AuthTokens.sign(c -> c.claim("role", null))),
                Arguments.of("missing_user_id", AuthTokens.sign(c -> c.subject(null))),
                Arguments.of("malformed", "not-a-token"),
                Arguments.of("malformed", "a.b.c"),
                Arguments.of("unsupported_token_type", AuthTokens.sign(c -> c.claim("token_use", "refresh"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusals")
    @DisplayName("a refused token comes back as its reason and no detail")
    void refused(String reason, String token) {
        Map<String, Object> answer = check(token);

        assertThat(answer).containsEntry("valid", false).containsEntry(REASON, reason);
        assertThat(answer.get("role")).isNull();
        assertThat(answer.toString()).doesNotContain(token).doesNotContain(SECRET);
    }

    @Test
    @DisplayName("a good token with a single role: role, user id and variables")
    void acceptedSingleRole() {
        Map<String, Object> answer = check(AuthTokens.sign(c -> { }));

        assertThat(answer).containsEntry("valid", true).containsEntry(REASON, null).containsEntry("role", "user")
                .containsEntry("userId", "u-1");
        assertThat(answer.get("allowedRoles")).isEqualTo(List.of("user"));
        @SuppressWarnings("unchecked")
        Map<String, String> variables = (Map<String, String>) answer.get("sessionVariables");
        assertThat(variables).containsEntry("x-excalibase-user-id", "u-1").containsEntry("x-excalibase-role", "user")
                .containsEntry("x-excalibase-project-id", "proj");
    }

    @Test
    @DisplayName("no verifier at all is its own reason, not a failure")
    void noVerifier() {
        Map<String, Object> answer = TokenCheck.run(null, "x.y.z".getBytes(StandardCharsets.UTF_8), null);

        assertThat(answer).containsEntry("valid", false).containsEntry(REASON, "no_verifier");
    }

    @Test
    @DisplayName("a verifier that throws something unexpected is malformed, never its message")
    void unexpectedFailure() {
        TokenVerifier broken = (token, project) -> {
            throw new IllegalStateException("secret detail " + token);
        };

        Map<String, Object> answer = TokenCheck.run(broken, "tok".getBytes(StandardCharsets.UTF_8), null);

        assertThat(answer).containsEntry(REASON, "malformed");
        assertThat(answer.toString()).doesNotContain("secret detail");
    }

    @Test
    @DisplayName("an unknown refusal text is malformed")
    void unknownRefusal() {
        TokenVerifier odd = (token, project) -> {
            throw new JwtVerificationException("claim fragment alice@example.com");
        };

        Map<String, Object> answer = TokenCheck.run(odd, "tok".getBytes(StandardCharsets.UTF_8), null);

        assertThat(answer).containsEntry(REASON, "malformed");
        assertThat(answer.toString()).doesNotContain("alice");
    }

    @Test
    @DisplayName("a body that is not UTF-8, or empty, is malformed")
    void notText() {
        assertThat(TokenCheck.run(VERIFIER, new byte[] {(byte) 0xff, (byte) 0xfe}, null)).containsEntry(REASON, "malformed");
        assertThat(TokenCheck.run(VERIFIER, new byte[0], null)).containsEntry(REASON, "malformed");
        assertThat(TokenCheck.run(VERIFIER, null, null)).containsEntry(REASON, "malformed");
    }

    @Test
    @DisplayName("whitespace around the token is not part of it")
    void trimmed() {
        assertThat(check("  " + AuthTokens.sign(c -> { }) + "\n")).containsEntry("valid", true);
    }
}
