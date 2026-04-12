package io.github.excalibase.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

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

    private String signJwt(ECPrivateKey key, long userId, String projectId, String role, String email, Instant exp) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(email)
                .claim("userId", userId)
                .claim("projectId", projectId)
                .claim("orgSlug", "duc-corp")
                .claim("projectName", "app-a")
                .claim("role", role)
                .issuer("excalibase")
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(exp))
                .build();

        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), claims);
        signed.sign(new ECDSASigner(key));
        return signed.serialize();
    }

    @Test
    void validToken_returnsClaims() throws Exception {
        String token = signJwt(privateKey, 42, "my-project", "user", "alice@test.com",
                Instant.now().plusSeconds(3600));

        JwtClaims claims = jwtService.verify(token);

        assertEquals("42", claims.userId());
        assertEquals("my-project", claims.projectId());
        assertEquals("duc-corp", claims.orgSlug());
        assertEquals("app-a", claims.projectName());
        assertEquals("user", claims.role());
        assertEquals("alice@test.com", claims.email());
    }

    @Test
    void expiredToken_throws() throws Exception {
        String token = signJwt(privateKey, 1, "p", "user", "a@b.com",
                Instant.now().minusSeconds(60));

        assertThrows(JwtVerificationException.class, () -> jwtService.verify(token));
    }

    @Test
    void wrongKey_throws() throws Exception {
        String token = signJwt(wrongPrivateKey, 1, "p", "user", "a@b.com",
                Instant.now().plusSeconds(3600));

        assertThrows(JwtVerificationException.class, () -> jwtService.verify(token));
    }

    @Test
    void tamperedToken_throws() throws Exception {
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

    // ─── Public Key Cache Tests ──────────────────────────────────────────────────

    @Nested
    class PublicKeyCacheTest {

        static HttpServer mockJwks;
        static int jwksPort;
        static AtomicInteger fetchCount;
        static ECPrivateKey cacheTestPrivateKey;
        static ECPublicKey cacheTestPublicKey;

        @BeforeAll
        static void startMockJwks() throws Exception {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair kp = gen.generateKeyPair();
            cacheTestPrivateKey = (ECPrivateKey) kp.getPrivate();
            cacheTestPublicKey = (ECPublicKey) kp.getPublic();

            fetchCount = new AtomicInteger(0);
            mockJwks = HttpServer.create(new InetSocketAddress(0), 0);
            jwksPort = mockJwks.getAddress().getPort();

            String jwksJson = buildJwks(cacheTestPublicKey);

            mockJwks.createContext("/.well-known/jwks.json", exchange -> {
                fetchCount.incrementAndGet();
                byte[] body = jwksJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.getResponseBody().close();
            });
            mockJwks.start();
        }

        @AfterAll
        static void stopMockJwks() {
            if (mockJwks != null) mockJwks.stop(0);
        }

        @Test
        @DisplayName("constructor eagerly fetches JWKS on startup")
        void constructor_fetchesFromJwks() {
            fetchCount.set(0);
            new JwtService("http://localhost:" + jwksPort + "/.well-known/jwks.json", 30);
            assertEquals(1, fetchCount.get());
        }

        @Test
        @DisplayName("verify uses cached keys — no additional JWKS fetches")
        void verify_usesCachedKey_noExtraFetch() throws Exception {
            fetchCount.set(0);
            var svc = new JwtService("http://localhost:" + jwksPort + "/.well-known/jwks.json", 30);
            assertEquals(1, fetchCount.get());

            for (int i = 0; i < 3; i++) {
                JWTClaimsSet claims = new JWTClaimsSet.Builder()
                        .subject("test@test.com")
                        .claim("userId", 1L)
                        .claim("projectId", "test/proj")
                        .issuer("excalibase")
                        .issueTime(Date.from(Instant.now()))
                        .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                        .build();
                SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), claims);
                signed.sign(new ECDSASigner(cacheTestPrivateKey));
                svc.verify(signed.serialize());
            }

            assertEquals(1, fetchCount.get(), "Should not re-fetch — keys are cached");
        }

        private static String buildJwks(ECPublicKey key) throws Exception {
            com.nimbusds.jose.jwk.ECKey ecKey = new com.nimbusds.jose.jwk.ECKey.Builder(
                    com.nimbusds.jose.jwk.Curve.P_256, key)
                    .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE)
                    .keyID("test-key")
                    .build();
            com.nimbusds.jose.jwk.JWKSet jwkSet = new com.nimbusds.jose.jwk.JWKSet(ecKey);
            return jwkSet.toString();
        }
    }
}
