package io.github.excalibase.security;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    static ECPrivateKey privateKey;
    static ECPublicKey publicKey;
    static ECPrivateKey wrongPrivateKey;
    static JwtService jwtService;

    @BeforeAll
    static void setup() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));

        KeyPair kp = gen.generateKeyPair();
        privateKey = (ECPrivateKey) kp.getPrivate();
        publicKey = (ECPublicKey) kp.getPublic();

        KeyPair wrong = gen.generateKeyPair();
        wrongPrivateKey = (ECPrivateKey) wrong.getPrivate();

        jwtService = new JwtService(publicKey);
    }

    private String signJwt(ECPrivateKey key, long userId, String projectId, String role, String email, Instant exp) {
        return Jwts.builder()
                .subject(email)
                .claim("userId", userId)
                .claim("projectId", projectId)
                .claim("orgSlug", "duc-corp")
                .claim("projectName", "app-a")
                .claim("role", role)
                .issuer("excalibase")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
    }

    @Test
    void validToken_returnsClaims() {
        String token = signJwt(privateKey, 42, "my-project", "user", "alice@test.com",
                Instant.now().plusSeconds(3600));

        JwtClaims claims = jwtService.verify(token);

        assertEquals(42, claims.userId());
        assertEquals("my-project", claims.projectId());
        assertEquals("duc-corp", claims.orgSlug());
        assertEquals("app-a", claims.projectName());
        assertEquals("user", claims.role());
        assertEquals("alice@test.com", claims.email());
    }

    @Test
    void expiredToken_throws() {
        String token = signJwt(privateKey, 1, "p", "user", "a@b.com",
                Instant.now().minusSeconds(60));

        assertThrows(JwtVerificationException.class, () -> jwtService.verify(token));
    }

    @Test
    void wrongKey_throws() {
        String token = signJwt(wrongPrivateKey, 1, "p", "user", "a@b.com",
                Instant.now().plusSeconds(3600));

        assertThrows(JwtVerificationException.class, () -> jwtService.verify(token));
    }

    @Test
    void tamperedToken_throws() {
        String token = signJwt(privateKey, 1, "p", "user", "a@b.com",
                Instant.now().plusSeconds(3600));
        // Flip a character in the payload
        String tampered = token.substring(0, token.lastIndexOf('.') - 1) + "X" +
                token.substring(token.lastIndexOf('.'));

        assertThrows(JwtVerificationException.class, () -> jwtService.verify(tampered));
    }

    @Test
    void malformedToken_throws() {
        assertThrows(JwtVerificationException.class, () -> jwtService.verify("not.a.jwt"));
    }
}
