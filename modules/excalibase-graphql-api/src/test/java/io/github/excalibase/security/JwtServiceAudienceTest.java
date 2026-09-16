package io.github.excalibase.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * EXC-11: a token minted for one project must not be accepted by another.
 * excalibase-auth stamps every token with {@code aud: ["excalibase:<projectId>"]};
 * this verifier requires that entry to match the project in the request path.
 */
class JwtServiceAudienceTest {

    private static final String PROJECT = "proj_abc";
    private static final String OTHER_PROJECT = "proj_xyz";
    private static final String AUD_PREFIX = "excalibase:";
    private static final String EXPECTED_AUD = AUD_PREFIX + PROJECT;

    static ECPrivateKey privateKey;
    static ECPublicKey publicKey;

    @BeforeAll
    static void setup() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = gen.generateKeyPair();
        privateKey = (ECPrivateKey) kp.getPrivate();
        publicKey = (ECPublicKey) kp.getPublic();
    }

    /** A verifier that enforces the audience, as production does by default. */
    private JwtService requiringAud() {
        return new JwtService(publicKey).requireAudience(true, AUD_PREFIX);
    }

    private JwtService notRequiringAud() {
        return new JwtService(publicKey).requireAudience(false, AUD_PREFIX);
    }

    private JWTClaimsSet.Builder baseClaims() {
        return new JWTClaimsSet.Builder()
                .subject("alice@test.com")
                .claim("userId", 42)
                .claim("projectId", PROJECT)
                .claim("role", "user")
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)));
    }

    private String sign(JWTClaimsSet claims) throws Exception {
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), claims);
        signed.sign(new ECDSASigner(privateKey));
        return signed.serialize();
    }

    // ─── EXC-11: audience binding ───────────────────────────────────────────

    @Test
    @DisplayName("token minted for the requested project is accepted")
    void matchingAudience_accepted() throws Exception {
        String token = sign(baseClaims().audience(EXPECTED_AUD).build());

        JwtClaims claims = requiringAud().verify(token, PROJECT);

        assertEquals(PROJECT, claims.projectId());
    }

    @Test
    @DisplayName("token minted for another project is rejected as aud_mismatch")
    void audienceForAnotherProject_rejected() throws Exception {
        String token = sign(baseClaims().audience(AUD_PREFIX + OTHER_PROJECT).build());

        JwtVerificationException thrown = assertThrows(JwtVerificationException.class,
                () -> requiringAud().verify(token, PROJECT));

        assertEquals(JwtVerificationException.AUD_MISMATCH, thrown.code());
    }

    @Test
    @DisplayName("missing aud is rejected when the requirement is on")
    void missingAudience_rejectedWhenRequired() throws Exception {
        String token = sign(baseClaims().build());

        JwtVerificationException thrown = assertThrows(JwtVerificationException.class,
                () -> requiringAud().verify(token, PROJECT));

        assertEquals(JwtVerificationException.AUD_MISMATCH, thrown.code());
    }

    @Test
    @DisplayName("missing aud is accepted when the requirement is off — phased rollout")
    void missingAudience_acceptedWhenNotRequired() throws Exception {
        String token = sign(baseClaims().build());

        assertDoesNotThrow(() -> notRequiringAud().verify(token, PROJECT));
    }

    @Test
    @DisplayName("a mismatched aud is also tolerated when the requirement is off")
    void mismatchedAudience_acceptedWhenNotRequired() throws Exception {
        String token = sign(baseClaims().audience(AUD_PREFIX + OTHER_PROJECT).build());

        assertDoesNotThrow(() -> notRequiringAud().verify(token, PROJECT));
    }

    @Test
    @DisplayName("aud carrying several entries is accepted when one of them matches")
    void multiValuedAudience_acceptedWhenOneMatches() throws Exception {
        String token = sign(baseClaims()
                .audience(List.of("some-other-service", EXPECTED_AUD))
                .build());

        assertDoesNotThrow(() -> requiringAud().verify(token, PROJECT));
    }

    @Test
    @DisplayName("an empty aud array is rejected")
    void emptyAudience_rejected() throws Exception {
        String token = sign(baseClaims().audience(List.of()).build());

        JwtVerificationException thrown = assertThrows(JwtVerificationException.class,
                () -> requiringAud().verify(token, PROJECT));

        assertEquals(JwtVerificationException.AUD_MISMATCH, thrown.code());
    }

    @Test
    @DisplayName("a configured prefix other than the default is honoured")
    void customAudiencePrefix_honoured() throws Exception {
        JwtService service = new JwtService(publicKey).requireAudience(true, "tenant/");

        assertDoesNotThrow(() -> service.verify(sign(baseClaims().audience("tenant/" + PROJECT).build()), PROJECT));
        assertThrows(JwtVerificationException.class,
                () -> service.verify(sign(baseClaims().audience(EXPECTED_AUD).build()), PROJECT));
    }

    @Test
    @DisplayName("with no project in the path the token's own projectId sets the expected audience")
    void nullExpectedProject_fallsBackToTokenProjectId() throws Exception {
        assertDoesNotThrow(() -> requiringAud().verify(sign(baseClaims().audience(EXPECTED_AUD).build()), null));

        assertThrows(JwtVerificationException.class,
                () -> requiringAud().verify(sign(baseClaims().audience(AUD_PREFIX + OTHER_PROJECT).build()), null));
    }

    @Test
    @DisplayName("single-argument verify keeps working and still enforces the audience")
    void singleArgVerify_stillEnforcesAudience() throws Exception {
        assertDoesNotThrow(() -> requiringAud().verify(sign(baseClaims().audience(EXPECTED_AUD).build())));

        assertThrows(JwtVerificationException.class,
                () -> requiringAud().verify(sign(baseClaims().audience(AUD_PREFIX + OTHER_PROJECT).build())));
    }

    // ─── EXC-11: refresh credentials are not access tokens ──────────────────

    @Test
    @DisplayName("a refresh credential is refused on an API call")
    void refreshTokenUse_rejected() throws Exception {
        String token = sign(baseClaims()
                .audience(EXPECTED_AUD)
                .claim("token_use", "refresh")
                .build());

        JwtVerificationException thrown = assertThrows(JwtVerificationException.class,
                () -> requiringAud().verify(token, PROJECT));

        assertEquals(JwtVerificationException.REFRESH_TOKEN_NOT_ACCEPTED, thrown.code());
    }

    @Test
    @DisplayName("a refresh credential is refused even with the audience requirement off")
    void refreshTokenUse_rejectedEvenWhenAudNotRequired() throws Exception {
        String token = sign(baseClaims().claim("token_use", "refresh").build());

        JwtVerificationException thrown = assertThrows(JwtVerificationException.class,
                () -> notRequiringAud().verify(token, PROJECT));

        assertEquals(JwtVerificationException.REFRESH_TOKEN_NOT_ACCEPTED, thrown.code());
    }

    @Test
    @DisplayName("token_use=access and tokens with no token_use are both accepted")
    void accessAndLegacyTokenUse_accepted() throws Exception {
        assertDoesNotThrow(() -> requiringAud().verify(
                sign(baseClaims().audience(EXPECTED_AUD).claim("token_use", "access").build()), PROJECT));

        assertDoesNotThrow(() -> requiringAud().verify(
                sign(baseClaims().audience(EXPECTED_AUD).build()), PROJECT));
    }

    @Test
    @DisplayName("email_verified travels through as an extra claim")
    void emailVerifiedClaim_exposed() throws Exception {
        String token = sign(baseClaims().audience(EXPECTED_AUD).claim("email_verified", true).build());

        JwtClaims claims = requiringAud().verify(token, PROJECT);

        assertEquals(Boolean.TRUE, claims.extraClaims().get("email_verified"));
    }

    @Test
    @DisplayName("a verifier left unconfigured requires the audience by default")
    void defaultVerifier_requiresAudience() throws Exception {
        JwtService service = new JwtService(publicKey);

        assertDoesNotThrow(() -> service.verify(sign(baseClaims().audience(EXPECTED_AUD).build()), PROJECT));
        assertThrows(JwtVerificationException.class,
                () -> service.verify(sign(baseClaims().build()), PROJECT));
    }
}
