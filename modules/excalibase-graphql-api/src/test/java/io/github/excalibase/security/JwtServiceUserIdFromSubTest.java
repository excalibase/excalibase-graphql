package io.github.excalibase.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** EXC-879: the end-user id is the token's {@code sub}, never a custom claim. */
class JwtServiceUserIdFromSubTest {

    private static final String PROJECT = "proj_abc";
    private static final String AUDIENCE_PREFIX = "excalibase:";
    private static final String ROLE_CLAIM = "role";
    private static final String USER_ROLE = "user";
    private static final String PROJECT_CLAIM = "projectId";
    private static final String USER_ID_VARIABLE = "x-excalibase-user-id";
    private static final String REAL_USER = "5b1c0d2e-real";
    private static final String FORGED_USER = "victim-user";

    private static ECPrivateKey privateKey;
    private static ECPublicKey publicKey;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        privateKey = (ECPrivateKey) pair.getPrivate();
        publicKey = (ECPublicKey) pair.getPublic();
    }

    private static JWTClaimsSet.Builder platformClaims() {
        return new JWTClaimsSet.Builder()
                .subject(REAL_USER)
                .claim("email", "alice@test.com")
                .claim(PROJECT_CLAIM, PROJECT)
                .claim(ROLE_CLAIM, USER_ROLE)
                .audience(AUDIENCE_PREFIX + PROJECT)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)));
    }

    private static Map<String, String> variablesOf(JWTClaimsSet claims) throws Exception {
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), claims);
        signed.sign(new ECDSASigner(privateKey));
        JwtClaims verified = new JwtService(publicKey).requireAudience(true, AUDIENCE_PREFIX)
                .verify(signed.serialize(), PROJECT);
        return new Principal(USER_ROLE, false, verified, Map.of()).sessionVariables(PROJECT);
    }

    @Test
    @DisplayName("a platform token (sub plus an explicit email) gets its user id from sub")
    void platformToken_userIdIsSub() throws Exception {
        assertThat(variablesOf(platformClaims().build()))
                .containsEntry(USER_ID_VARIABLE, REAL_USER)
                .containsEntry("x-excalibase-email", "alice@test.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {"user-id", "User-Id", "USER-ID", "user_id", "USER_ID", "User_Id", "userId", "userid",
            "user"})
    @DisplayName("a custom claim named like the user id can neither set nor override it")
    void customUserIdClaim_neverOverridesSub(String claimName) throws Exception {
        Map<String, String> variables = variablesOf(platformClaims().claim(claimName, FORGED_USER).build());

        assertThat(variables).containsEntry(USER_ID_VARIABLE, REAL_USER);
        assertThat(variables.values()).doesNotContain(FORGED_USER);
    }

    @Test
    @DisplayName("a token with no sub is refused, even when it carries a user-id claim")
    void noSub_refused() {
        JWTClaimsSet forged = new JWTClaimsSet.Builder()
                .claim("user-id", FORGED_USER).claim("userId", FORGED_USER)
                .claim(PROJECT_CLAIM, PROJECT).claim(ROLE_CLAIM, USER_ROLE)
                .audience(AUDIENCE_PREFIX + PROJECT)
                .expirationTime(Date.from(Instant.now().plusSeconds(3600))).build();

        assertThatThrownBy(() -> variablesOf(forged)).isInstanceOf(JwtVerificationException.class);
    }

    @Test
    @DisplayName("other custom claims still become session variables")
    void otherCustomClaims_stillWork() throws Exception {
        assertThat(variablesOf(platformClaims().claim("plan", "gold").build()))
                .containsEntry("x-excalibase-plan", "gold");
    }
}
