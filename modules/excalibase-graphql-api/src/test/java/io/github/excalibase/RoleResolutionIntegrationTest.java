package io.github.excalibase;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static io.github.excalibase.support.Expect.jsonPath;
import static io.github.excalibase.support.Expect.status;

/**
 * Over HTTP against real Postgres: every request runs as one role — anon without a token,
 * the token's role, or an allowed role picked with {@code X-Excalibase-Role} — and the
 * permissions that apply are exactly that role's. {@code service} sees everything.
 */
class RoleResolutionIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "proj-roles";
    private static final String ROLE_HEADER = "X-Excalibase-Role";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-exposure.sql");

    static ECPrivateKey privateKey;
    static HttpServer mockJwks;

    private static final AtomicReference<String> PERMISSIONS = new AtomicReference<>();
    private static final AtomicLong VERSION = new AtomicLong();

    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair keyPair = generator.generateKeyPair();
            privateKey = (ECPrivateKey) keyPair.getPrivate();
            String jwksJson = new JWKSet(new ECKey.Builder(Curve.P_256, (ECPublicKey) keyPair.getPublic())
                    .keyUse(KeyUse.SIGNATURE).keyID("test-key").build()).toString();
            mockJwks = HttpServer.create(new InetSocketAddress(0), 0);
            mockJwks.createContext("/.well-known/jwks.json", exchange -> {
                byte[] payload = jwksJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
                exchange.getResponseBody().close();
            });
            PermissionDocs.serve(mockJwks, PROJECT, PERMISSIONS::get);
            mockJwks.start();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to set up mock JWKS", e);
        }
    }

    @AfterAll
    static void teardown() {
        mockJwks.stop(0);
    }

    @Override
    public Map<String, String> getProperties() {
        postgres.start();
        Map<String, String> properties = EngineProperties.isolated();
        properties.put("spring.datasource.url", postgres.getJdbcUrl());
        properties.put("spring.datasource.username", postgres.getUsername());
        properties.put("spring.datasource.password", postgres.getPassword());
        properties.put("app.database-type", "postgres");
        properties.put("app.security.jwt-enabled", "true");
        properties.put("app.project-id", String.valueOf(PROJECT));
        properties.put("app.security.auth.jwks-url", String.valueOf("http://localhost:" + mockJwks.getAddress().getPort() + "/.well-known/jwks.json"));
        properties.put("app.security.rls.policy-url", String.valueOf("http://localhost:" + mockJwks.getAddress().getPort() + "/api"));
        properties.put("app.security.rls.policy-pat", "test-pat");
        properties.put("app.security.rls.policy-ttl-ms", String.valueOf(0));
        return properties;
    }


    private static void permit(String... entries) {
        PERMISSIONS.set(PermissionDocs.document(PROJECT, VERSION.incrementAndGet(), entries));
    }

    /** anon may read orders, user may read secrets, editor may read customer — nothing else. */
    @BeforeEach
    void permitOneTablePerRole() {
        permit(PermissionDocs.entry("public.orders", "anon", PermissionDocs.selectAll()),
                PermissionDocs.entry("public.secrets", "user", PermissionDocs.selectAll()),
                PermissionDocs.entry("public.customer", "editor", PermissionDocs.selectAll()));
    }

    private static String token(Map<String, Object> roleClaims) throws Exception {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .subject("u@test.com")
                .claim("userId", "u-1")
                .claim("projectId", PROJECT)
                .audience("excalibase:" + PROJECT)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)));
        roleClaims.forEach(builder::claim);
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), builder.build());
        signed.sign(new ECDSASigner(privateKey));
        return signed.serialize();
    }

    private static final Map<String, Object> USER = Map.of("role", "user");
    private static final Map<String, Object> EDITOR_CAPABLE =
            Map.of("role", "user", "allowed_roles", List.of("user", "editor"));
    private static final Map<String, Object> SERVICE = Map.of("role", "service", "scope", "service");

    private HttpAnswer graphql(Map<String, Object> roleClaims, String requestedRole, String query)
            throws Exception {
        List<String> headers = new ArrayList<>(List.of("Content-Type", "application/json"));
        if (roleClaims != null) {
            headers.addAll(List.of("Authorization", "Bearer " + token(roleClaims)));
        }
        if (requestedRole != null) {
            headers.addAll(List.of(ROLE_HEADER, requestedRole));
        }
        return http.post("/" + PROJECT + "/graphql", MAPPER.writeValueAsString(Map.of("query", query)),
                headers.toArray(String[]::new));
    }

    private void assertReadable(Map<String, Object> roleClaims, String requestedRole, String field)
            throws Exception {
        graphql(roleClaims, requestedRole, "{ " + field + " { id } }")
                .expect(status().isOk())
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data." + field, hasSize(1)));
    }

    private void assertAbsent(Map<String, Object> roleClaims, String requestedRole, String field)
            throws Exception {
        graphql(roleClaims, requestedRole, "{ " + field + " { id } }")
                .expect(status().isOk())
                .expect(jsonPath("$.errors[0].message", containsString("Unknown field")));
    }

    @Test
    void noToken_runsAsAnon() throws Exception {
        graphql(null, null, "{ publicOrders { id } }")
                .expect(status().isOk())
                .expect(jsonPath("$.data.publicOrders", hasSize(2)));
        assertAbsent(null, null, "publicSecrets");
        assertAbsent(null, null, "publicCustomer");
    }

    @Test
    void userToken_runsAsUser() throws Exception {
        assertReadable(USER, null, "publicSecrets");
        assertAbsent(USER, null, "publicOrders");
        assertAbsent(USER, null, "publicCustomer");
    }

    @Test
    void roleHeaderOutsideAllowedRoles_isForbidden() throws Exception {
        graphql(USER, "editor", "{ publicCustomer { id } }")
                .expect(status().isForbidden())
                .expect(jsonPath("$.errors[0].extensions.code").value("role_not_allowed"));
    }

    @Test
    void noTokenWithUserRoleHeader_isForbidden() throws Exception {
        graphql(null, "user", "{ publicSecrets { id } }")
                .expect(status().isForbidden())
                .expect(jsonPath("$.errors[0].extensions.code").value("role_not_allowed"));
    }

    @Test
    void tokenWithoutRole_isUnauthorized() throws Exception {
        graphql(Map.of(), null, "{ publicOrders { id } }")
                .expect(status().isUnauthorized())
                .expect(jsonPath("$.errors[0].extensions.code").value("invalid_role_claim"));
    }

    @Test
    void allowedRoleHeader_appliesThatRolesGrants() throws Exception {
        assertReadable(EDITOR_CAPABLE, "editor", "publicCustomer");
        assertAbsent(EDITOR_CAPABLE, "editor", "publicSecrets");
        assertReadable(EDITOR_CAPABLE, null, "publicSecrets");
    }

    @Test
    void serviceToken_seesTheWholeSchema() throws Exception {
        assertReadable(SERVICE, null, "publicSecrets");
        assertReadable(SERVICE, null, "publicCustomer");
        graphql(SERVICE, null, "{ publicOrders { id } }")
                .expect(jsonPath("$.data.publicOrders", hasSize(2)));
    }

    @Test
    void serviceTokenActingAsUser_getsTheUsersSchema() throws Exception {
        assertReadable(SERVICE, "user", "publicSecrets");
        assertAbsent(SERVICE, "user", "publicOrders");
        assertAbsent(SERVICE, "user", "publicCustomer");
    }

    @Test
    void serviceRoleWithoutServiceScope_isUnauthorized() throws Exception {
        graphql(Map.of("role", "service"), null, "{ publicSecrets { id } }")
                .expect(status().isUnauthorized())
                .expect(jsonPath("$.errors[0].extensions.code").value("invalid_role_claim"));
    }

    /**
     * A publishable key's token has no userId and sub "apikey:<id>"; anon has no user, so a filter on
     * the user id fails the request with missing_session_variable rather than guessing a value.
     */
    @Test
    void anonKeyToken_againstAnOwnerFilter_failsWithMissingSessionVariable() throws Exception {
        permit(PermissionDocs.entry("public.orders", "anon",
                PermissionDocs.select("{\"id\":{\"_eq\":\"X-Excalibase-User-Id\"}}", "\"*\"")));
        JWTClaimsSet anonKey = new JWTClaimsSet.Builder()
                .subject("apikey:5")
                .claim("projectId", PROJECT)
                .claim("role", "anon")
                .claim("scope", "public")
                .claim("keyId", 5L)
                .audience("excalibase:" + PROJECT)
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .build();
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), anonKey);
        signed.sign(new ECDSASigner(privateKey));

        http.post("/" + PROJECT + "/graphql", MAPPER.writeValueAsString(Map.of("query", "{ publicOrders { id } }")), "Authorization", "Bearer " + signed.serialize(), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.errors[0].extensions.code").value("missing_session_variable"))
                .expect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void rest_followsTheSameRole() throws Exception {
        http.get("/" + PROJECT + "/api/v1/secrets", "Accept-Profile", "public")
                .expect(status().isNotFound());
        http.get("/" + PROJECT + "/api/v1/secrets", "Accept-Profile", "public", "Authorization", "Bearer " + token(USER))
                .expect(status().isOk());
        http.get("/" + PROJECT + "/api/v1/customer", "Accept-Profile", "public", "Authorization", "Bearer " + token(SERVICE))
                .expect(status().isOk());
        http.get("/" + PROJECT + "/api/v1/customer", "Accept-Profile", "public", "Authorization", "Bearer " + token(USER), ROLE_HEADER, "editor")
                .expect(status().isForbidden());
    }
}
