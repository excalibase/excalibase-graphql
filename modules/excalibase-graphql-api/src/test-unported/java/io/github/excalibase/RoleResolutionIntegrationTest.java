package io.github.excalibase;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Over HTTP against real Postgres: every request runs as one role — anon without a token,
 * the token's role, or an allowed role picked with {@code X-Excalibase-Role} — and the
 * permissions that apply are exactly that role's. {@code service} sees everything.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RoleResolutionIntegrationTest {

    private static final String PROJECT = "proj-roles";
    private static final String ROLE_HEADER = "X-Excalibase-Role";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
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

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.database-type", () -> "postgres");
        registry.add("app.security.jwt-enabled", () -> "true");
        registry.add("app.project-id", () -> PROJECT);
        registry.add("app.security.auth.jwks-url",
                () -> "http://localhost:" + mockJwks.getAddress().getPort() + "/.well-known/jwks.json");
        registry.add("app.security.rls.policy-url",
                () -> "http://localhost:" + mockJwks.getAddress().getPort() + "/api");
        registry.add("app.security.rls.policy-pat", () -> "test-pat");
        registry.add("app.security.rls.policy-ttl-ms", () -> 0);
    }

    @Autowired
    private MockMvc mockMvc;

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

    private ResultActions graphql(Map<String, Object> roleClaims, String requestedRole, String query)
            throws Exception {
        MockHttpServletRequestBuilder request = post("/" + PROJECT + "/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("query", query)));
        if (roleClaims != null) {
            request.header("Authorization", "Bearer " + token(roleClaims));
        }
        if (requestedRole != null) {
            request.header(ROLE_HEADER, requestedRole);
        }
        return mockMvc.perform(request);
    }

    private void assertReadable(Map<String, Object> roleClaims, String requestedRole, String field)
            throws Exception {
        graphql(roleClaims, requestedRole, "{ " + field + " { id } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data." + field, hasSize(1)));
    }

    private void assertAbsent(Map<String, Object> roleClaims, String requestedRole, String field)
            throws Exception {
        graphql(roleClaims, requestedRole, "{ " + field + " { id } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].message", containsString("Unknown field")));
    }

    @Test
    void noToken_runsAsAnon() throws Exception {
        graphql(null, null, "{ publicOrders { id } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publicOrders", hasSize(2)));
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
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].extensions.code").value("role_not_allowed"));
    }

    @Test
    void noTokenWithUserRoleHeader_isForbidden() throws Exception {
        graphql(null, "user", "{ publicSecrets { id } }")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].extensions.code").value("role_not_allowed"));
    }

    @Test
    void tokenWithoutRole_isUnauthorized() throws Exception {
        graphql(Map.of(), null, "{ publicOrders { id } }")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errors[0].extensions.code").value("invalid_role_claim"));
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
                .andExpect(jsonPath("$.data.publicOrders", hasSize(2)));
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
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errors[0].extensions.code").value("invalid_role_claim"));
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

        mockMvc.perform(post("/" + PROJECT + "/graphql")
                        .header("Authorization", "Bearer " + signed.serialize())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("query", "{ publicOrders { id } }"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.code").value("missing_session_variable"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void rest_followsTheSameRole() throws Exception {
        mockMvc.perform(get("/" + PROJECT + "/api/v1/secrets").header("Accept-Profile", "public"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/" + PROJECT + "/api/v1/secrets").header("Accept-Profile", "public")
                        .header("Authorization", "Bearer " + token(USER)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/" + PROJECT + "/api/v1/customer").header("Accept-Profile", "public")
                        .header("Authorization", "Bearer " + token(SERVICE)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/" + PROJECT + "/api/v1/customer").header("Accept-Profile", "public")
                        .header("Authorization", "Bearer " + token(USER)).header(ROLE_HEADER, "editor"))
                .andExpect(status().isForbidden());
    }
}
