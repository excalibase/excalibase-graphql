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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
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
import java.util.Map;

import static io.github.excalibase.PermissionDocs.entry;
import static io.github.excalibase.PermissionDocs.everything;
import static io.github.excalibase.PermissionDocs.insert;
import static io.github.excalibase.PermissionDocs.select;
import static io.github.excalibase.PermissionDocs.update;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * EXC-559: a request the database refuses for a reason the client can fix answers 4xx with that reason
 * (409 for a duplicate, 400 for a foreign key, check, not-null, exclusion, raised message or malformed
 * value, 403 for a column the role may not write), on REST and GraphQL alike, and never the PL/pgSQL
 * context, SQL text or stack of the failure.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class DataApiErrorsIntegrationTest {

    private static final String PROJECT = "proj-data-errors";
    private static final String ROLE = "user";
    private static final String ORDER_COLUMNS = "[\"id\",\"customer_id\",\"qty\",\"ref\",\"placed_at\"]";
    private static final String PERMISSIONS = PermissionDocs.document(PROJECT,
            entry("public.customers", ROLE, everything()),
            entry("public.bookings", ROLE, everything()),
            entry("public.orders", ROLE, select("{}", "\"*\""),
                    insert("{}", ORDER_COLUMNS, "{\"owner_id\":\"X-Excalibase-User-Id\"}"),
                    update("{}", "{}", "[\"qty\"]", "{}")));

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-data-errors.sql");

    static ECPrivateKey privateKey;
    static HttpServer mockControlPlane;

    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair keyPair = generator.generateKeyPair();
            privateKey = (ECPrivateKey) keyPair.getPrivate();
            mockControlPlane = HttpServer.create(new InetSocketAddress(0), 0);
            byte[] jwks = new JWKSet(new ECKey.Builder(Curve.P_256, (ECPublicKey) keyPair.getPublic())
                    .keyUse(KeyUse.SIGNATURE).keyID("test-key").build()).toString().getBytes(StandardCharsets.UTF_8);
            mockControlPlane.createContext("/.well-known/jwks.json", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                exchange.getResponseBody().write(jwks);
                exchange.close();
            });
            PermissionDocs.serve(mockControlPlane, PROJECT, () -> PERMISSIONS);
            mockControlPlane.start();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start the mock control plane", e);
        }
    }

    @AfterAll
    static void stopControlPlane() {
        mockControlPlane.stop(0);
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        String base = "http://localhost:" + mockControlPlane.getAddress().getPort();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.database-type", () -> "postgres");
        registry.add("app.security.jwt-enabled", () -> "true");
        registry.add("app.project-id", () -> PROJECT);
        registry.add("app.security.auth.jwks-url", () -> base + "/.well-known/jwks.json");
        registry.add("app.security.rls.policy-url", () -> base + "/api");
        registry.add("app.security.rls.policy-pat", () -> "test-pat");
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    private static String token() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("u@test.com").claim("userId", "u-1").claim("projectId", PROJECT)
                .claim("role", ROLE).claim("scope", "authenticated")
                .audience("excalibase:" + PROJECT).issuer("excalibase")
                .issueTime(Date.from(Instant.parse("2024-01-01T00:00:00Z")))
                .expirationTime(Date.from(Instant.parse("2099-01-01T00:00:00Z")))
                .build();
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), claims);
        signed.sign(new ECDSASigner(privateKey));
        return signed.serialize();
    }

    private ResultActions restPost(String table, String json) throws Exception {
        return mockMvc.perform(post("/" + PROJECT + "/api/v1/" + table)
                .header("Authorization", "Bearer " + token()).header("Content-Profile", "public")
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions restGet(String path) throws Exception {
        return mockMvc.perform(get("/" + PROJECT + "/api/v1/" + path)
                .header("Authorization", "Bearer " + token()).header("Accept-Profile", "public"));
    }

    private ResultActions graphql(String query) throws Exception {
        return mockMvc.perform(post("/" + PROJECT + "/graphql")
                .header("Authorization", "Bearer " + token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("query", query))));
    }

    @Nested
    class Rest {

        @Test
        void aDuplicate_isAConflictNamingTheConstraint() throws Exception {
            restPost("customers", "{\"id\":10,\"email\":\"alice@example.com\"}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("unique_violation"))
                    .andExpect(jsonPath("$.constraint").value("customers_email_key"))
                    .andExpect(jsonPath("$.message", containsString("customers_email_key")))
                    .andExpect(content().string(not(containsString("alice@example.com"))));
        }

        @Test
        void aMissingParent_isABadRequestNamingTheForeignKey() throws Exception {
            restPost("orders", "{\"id\":20,\"customer_id\":999,\"qty\":1}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("foreign_key_violation"))
                    .andExpect(jsonPath("$.constraint").value("orders_customer_id_fkey"));
        }

        @Test
        void aFailedCheck_isABadRequestNamingTheCheck() throws Exception {
            restPost("orders", "{\"id\":21,\"customer_id\":1,\"qty\":0}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("check_violation"))
                    .andExpect(jsonPath("$.constraint").value("orders_qty_check"));
        }

        @Test
        void aMissingRequiredValue_namesTheColumn() throws Exception {
            restPost("customers", "{\"id\":11}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("not_null_violation"))
                    .andExpect(jsonPath("$.column").value("email"));
        }

        @Test
        void anOverlap_isAnExclusionViolation() throws Exception {
            restPost("bookings", "{\"id\":2,\"during\":\"[2026-01-01 10:30, 2026-01-01 12:00)\"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("exclusion_violation"))
                    .andExpect(jsonPath("$.constraint").value("bookings_during_excl"));
        }

        @Test
        void aRaise_answersOnlyTheRaisedMessage() throws Exception {
            restPost("orders", "{\"id\":22,\"customer_id\":1,\"qty\":500}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("raised_exception"))
                    .andExpect(jsonPath("$.message").value("Order quantity 500 is above the limit"))
                    .andExpect(content().string(not(containsString("PL/pgSQL"))))
                    .andExpect(content().string(not(containsString("line"))));
        }

        @Test
        void aPresetColumn_isRefusedSayingTheServerSetsIt() throws Exception {
            restPost("orders", "{\"id\":23,\"customer_id\":1,\"qty\":1,\"owner_id\":\"someone-else\"}")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("permission_denied"))
                    .andExpect(jsonPath("$.message", containsString("set by the server")));
        }

        @Test
        void aColumnTheRoleMayNotUpdate_isForbidden() throws Exception {
            mockMvc.perform(patch("/" + PROJECT + "/api/v1/orders?id=eq.1")
                            .header("Authorization", "Bearer " + token()).header("Content-Profile", "public")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"customer_id\":2}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("permission_denied"))
                    .andExpect(jsonPath("$.column").value("customer_id"));
        }

        @Test
        void aColumnTheTableDoesNotHave_staysUnknown() throws Exception {
            restPost("orders", "{\"id\":24,\"customer_id\":1,\"qty\":1,\"colour\":\"red\"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error", containsString("Unknown column 'colour'")));
        }

        @Test
        void malformedFilterValues_areBadRequestsNamingTheColumn() throws Exception {
            restGet("orders?ref=eq.not-a-uuid")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("invalid_value"))
                    .andExpect(jsonPath("$.column").value("ref"));
            restGet("orders?qty=gt.many")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.column").value("qty"));
            restGet("orders?placed_at=gt.not-a-date")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("invalid_value"))
                    .andExpect(jsonPath("$.column").value("placed_at"));
        }
    }

    @Nested
    class Graphql {

        @Test
        void aDuplicate_carriesItsCodeAndConstraint() throws Exception {
            graphql("mutation { createPublicCustomers(input: { id: 12, email: \"bob@example.com\" }) { id } }")
                    .andExpect(jsonPath("$.errors[0].extensions.code").value("unique_violation"))
                    .andExpect(jsonPath("$.errors[0].extensions.constraint").value("customers_email_key"));
        }

        @Test
        void aRaise_neverLeaksThePlpgsqlContext() throws Exception {
            graphql("mutation { createPublicOrders(input: { id: 30, customer_id: 1, qty: 700 }) { id } }")
                    .andExpect(jsonPath("$.errors[0].message").value("Order quantity 700 is above the limit"))
                    .andExpect(jsonPath("$.errors[0].extensions.code").value("raised_exception"))
                    .andExpect(content().string(not(containsString("Where"))))
                    .andExpect(content().string(not(containsString("PL/pgSQL"))));
        }

        @Test
        void aPresetColumn_isPermissionDenied() throws Exception {
            graphql("mutation { createPublicOrders(input: { id: 31, customer_id: 1, qty: 1, owner_id: \"x\" }) { id } }")
                    .andExpect(jsonPath("$.errors[0].extensions.code").value("permission_denied"));
        }

        @Test
        void aQueryMixingIntrospectionAndData_answersBoth() throws Exception {
            graphql("query Mixed { __typename __schema { queryType { name } } "
                    + "publicCustomers(where: { id: { eq: 1 } }) { email } }")
                    .andExpect(jsonPath("$.errors").doesNotExist())
                    .andExpect(jsonPath("$.data.__typename").value("Query"))
                    .andExpect(jsonPath("$.data.__schema.queryType.name").value("Query"))
                    .andExpect(jsonPath("$.data.publicCustomers[0].email").value("alice@example.com"));
        }

        @Test
        void aDataErrorInAMixedQuery_isReported() throws Exception {
            graphql("{ __typename publicOrders(where: { ref: { eq: \"nope\" } }) { id } }")
                    .andExpect(jsonPath("$.errors[0].extensions.code").value("invalid_value"));
        }

        @Test
        void introspection_namesTheSubscriptions() throws Exception {
            graphql("{ __schema { subscriptionType { fields { name } } } }")
                    .andExpect(content().string(containsString("publicOrdersChanges")));
        }

        @Test
        void aMalformedFilterValue_namesTheColumn() throws Exception {
            graphql("{ publicOrders(where: { ref: { eq: \"not-a-uuid\" } }) { id } }")
                    .andExpect(jsonPath("$.errors[0].extensions.code").value("invalid_value"))
                    .andExpect(jsonPath("$.errors[0].extensions.column").value("ref"));
            graphql("{ publicOrders(where: { placed_at: { gt: \"not-a-date\" } }) { id } }")
                    .andExpect(jsonPath("$.errors[0].extensions.code").value("invalid_value"))
                    .andExpect(content().string(not(containsString("SELECT"))));
        }
    }
}
