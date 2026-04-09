package io.github.excalibase.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
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

    // ─── Public Key Cache Tests ──────────────────────────────────────────────────

    @Nested
    class PublicKeyCacheTest {

        static HttpServer mockVault;
        static int vaultPort;
        static AtomicInteger fetchCount;
        static ECPrivateKey cacheTestPrivateKey;
        static ECPublicKey cacheTestPublicKey;

        @BeforeAll
        static void startVault() throws Exception {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair kp = gen.generateKeyPair();
            cacheTestPrivateKey = (ECPrivateKey) kp.getPrivate();
            cacheTestPublicKey = (ECPublicKey) kp.getPublic();

            fetchCount = new AtomicInteger(0);
            mockVault = HttpServer.create(new InetSocketAddress(0), 0);
            vaultPort = mockVault.getAddress().getPort();

            String pubPem = toPem(cacheTestPublicKey);
            String keyJson = new ObjectMapper().writeValueAsString(
                Map.of("key", pubPem, "algorithm", "EC-P256"));

            mockVault.createContext("/api/vault/pki/public-key", exchange -> {
                fetchCount.incrementAndGet();
                byte[] body = keyJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.getResponseBody().close();
            });
            mockVault.start();
        }

        @AfterAll
        static void stopVault() {
            if (mockVault != null) mockVault.stop(0);
        }

        @Test
        @DisplayName("constructor eagerly fetches public key from vault")
        void constructor_fetchesFromVault() {
            fetchCount.set(0);
            new JwtService("http://localhost:" + vaultPort + "/api", 30);
            assertEquals(1, fetchCount.get());
        }

        @Test
        @DisplayName("verify uses cached key — no additional vault calls")
        void verify_usesCachedKey_noExtraFetch() {
            fetchCount.set(0);
            var svc = new JwtService("http://localhost:" + vaultPort + "/api", 30);
            assertEquals(1, fetchCount.get());

            // 3 verify calls — all use cached key
            for (int i = 0; i < 3; i++) {
                String token = Jwts.builder()
                    .subject("test@test.com")
                    .claim("userId", 1L)
                    .claim("projectId", "test/proj")
                    .issuer("excalibase")
                    .issuedAt(Date.from(Instant.now()))
                    .expiration(Date.from(Instant.now().plusSeconds(3600)))
                    .signWith(cacheTestPrivateKey)
                    .compact();
                svc.verify(token);
            }

            assertEquals(1, fetchCount.get(), "Should not re-fetch — key is cached");
        }

        private static String toPem(ECPublicKey key) {
            byte[] encoded = key.getEncoded();
            String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(encoded);
            return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----\n";
        }
    }
}
