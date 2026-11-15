package io.github.excalibase;

import io.github.excalibase.support.DatabaseAccess;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.excalibase.support.Expect.jsonPath;
import static io.github.excalibase.support.Expect.status;
import static io.github.excalibase.PermissionDocs.entry;
import static io.github.excalibase.PermissionDocs.insert;
import static io.github.excalibase.PermissionDocs.select;
import static io.github.excalibase.PermissionDocs.update;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * Findings of the prod user test (EXC-641) on real Postgres, with a schema shaped like its app: a create
 * answers the row when the role can read it only through a row a trigger added in the same statement; an
 * array relationship has its aggregate where the role may aggregate the child table; and a write the role's
 * filter refuses answers no rows rather than an error, as in Hasura and PostgREST.
 */
class TeamboardIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "proj-teamboard";
    private static final String USER_ID = "11111111-1111-1111-1111-111111111111";
    private static final String IS_MEMBER = "{\"publicWorkspaceMembers\":{\"user_id\":{\"_eq\":\"X-Excalibase-User-Id\"}}}";
    private static final String IS_OWNER = "{\"owner_id\":{\"_eq\":\"X-Excalibase-User-Id\"}}";
    private static final String ALL = "\"*\"";

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-teamboard.sql");

    private static final AtomicReference<String> PERMISSIONS = new AtomicReference<>();
    private static final AtomicLong VERSION = new AtomicLong();
    private static ECPrivateKey privateKey;
    private static HttpServer mockVault;
    private static int mockVaultPort;

    static {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair keyPair = gen.generateKeyPair();
            privateKey = (ECPrivateKey) keyPair.getPrivate();
            mockVault = HttpServer.create(new InetSocketAddress(0), 0);
            mockVaultPort = mockVault.getAddress().getPort();
            String jwksJson = new JWKSet(new ECKey.Builder(Curve.P_256, (ECPublicKey) keyPair.getPublic())
                    .keyUse(KeyUse.SIGNATURE).keyID("test-key").build()).toString();
            mockVault.createContext("/.well-known/jwks.json", exchange -> {
                byte[] payload = jwksJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
                exchange.getResponseBody().close();
            });
            PermissionDocs.serve(mockVault, PROJECT, PERMISSIONS::get);
            mockVault.start();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to set up mock vault", e);
        }
    }

    @AfterAll
    static void teardown() {
        if (mockVault != null) mockVault.stop(0);
    }

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = EngineProperties.datasource(postgres);
        properties.put("app.database-type", "postgres");
        properties.put("app.security.jwt-enabled", "true");
        properties.put("app.project-id", PROJECT);
        properties.put("app.security.auth.jwks-url", "http://localhost:" + mockVaultPort + "/.well-known/jwks.json");
        properties.put("app.security.rls.policy-url", "http://localhost:" + mockVaultPort + "/api");
        properties.put("app.security.rls.policy-pat", "test-pat");
        properties.put("app.security.rls.policy-ttl-ms", "0");
        return properties;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();


    private JdbcTemplate jdbc;

    @BeforeEach
    void openJdbcAndPermissions() {
        jdbc = DatabaseAccess.template(postgres);
        PERMISSIONS.set(PermissionDocs.document(PROJECT, VERSION.incrementAndGet(),
                entry("public.workspaces", "user", select(IS_MEMBER, ALL), insert("{}", "[\"name\",\"owner_id\"]", "{}"),
                        update(IS_OWNER, "{}", "[\"name\"]", "{}")),
                entry("public.workspace_members", "user", select("{\"user_id\":{\"_eq\":\"X-Excalibase-User-Id\"}}", ALL)),
                entry("public.projects", "user", select("{}", ALL)),
                entry("public.tasks", "user", select("{\"hidden\":{\"_eq\":false}}", ALL, null, true)),
                entry("public.projects", "viewer", select("{}", ALL)),
                entry("public.tasks", "viewer", select("{}", ALL, null, false))));
    }

    // ---- 1. a create readable only through a row its trigger added ----

    @Test
    void create_rowReadableOnlyThroughTheMembershipItsTriggerAdds_answersTheRow() throws Exception {
        as("user", "mutation { createPublicWorkspaces(input: { name: \"w-one\", owner_id: \"" + USER_ID
                + "\" }) { id name } }")
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.createPublicWorkspaces.name").value("w-one"));
    }

    @Test
    void createMany_rowsReadableOnlyThroughTheMembershipTheirTriggerAdds_answersTheRows() throws Exception {
        as("user", "mutation { createManyPublicWorkspaces(inputs: [{ name: \"w-two\", owner_id: \"" + USER_ID
                + "\" }, { name: \"w-three\", owner_id: \"" + USER_ID + "\" }]) { name } }")
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.createManyPublicWorkspaces[1].name").value("w-three"));
    }

    @Test
    void create_rowOfSomeoneElse_isWritten_butNotAnswered() throws Exception {
        as("user", "mutation { createPublicWorkspaces(input: { name: \"w-other\", owner_id: \"someone-else\" })"
                + " { name } }")
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.createPublicWorkspaces").value((Object) null));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspaces WHERE name = 'w-other'", Integer.class))
                .isEqualTo(1);
    }

    // ---- 3. a write the role's filter refuses answers no rows, as Hasura and PostgREST do ----

    @Test
    void update_ofARowTheRoleMayNotUpdate_answersNoRows_andChangesNothing() throws Exception {
        jdbc.update("INSERT INTO workspaces (name, owner_id) VALUES ('w-locked', 'someone-else')");

        as("user", "mutation { updatePublicWorkspaces(where: { name: { eq: \"w-locked\" } },"
                + " input: { name: \"stolen\" }) { name } }")
                .expect(status().isOk())
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.updatePublicWorkspaces").isEmpty());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspaces WHERE name = 'w-locked'", Integer.class))
                .isEqualTo(1);
    }

    // ---- 4. nested aggregates ----

    @Test
    void nestedAggregate_countsAndSumsTheRelatedRowsTheRoleMayRead() throws Exception {
        as("user", "{ publicProjects(orderBy: { id: ASC }) { name publicTasksAggregate { count sum { points } } } }")
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.publicProjects[0].publicTasksAggregate.count").value(2))
                .expect(jsonPath("$.data.publicProjects[0].publicTasksAggregate.sum.points").value(3))
                .expect(jsonPath("$.data.publicProjects[1].publicTasksAggregate.count").value(1));
    }

    @Test
    void nestedAggregate_takesItsOwnWhere() throws Exception {
        as("user", "{ publicProjects(orderBy: { id: ASC }) { big: publicTasksAggregate(where: { points: { gt: 1 } })"
                + " { count } } }")
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.publicProjects[0].big.count").value(1));
    }

    @Test
    void nestedAggregate_isInTheSchema_whereTheRoleMayAggregateTheChild() throws Exception {
        as("user", "{ __type(name: \"PublicProjects\") { fields { name } } }")
                .expect(jsonPath("$.data.__type.fields[*].name", hasItem("publicTasksAggregate")));
        as("viewer", "{ __type(name: \"PublicProjects\") { fields { name } } }")
                .expect(jsonPath("$.data.__type.fields[*].name", not(hasItem("publicTasksAggregate"))));
    }

    @Test
    void nestedAggregate_withoutAggregationsOnTheChild_isAnUnknownField() throws Exception {
        as("viewer", "{ publicProjects { publicTasksAggregate { count } } }")
                .expect(jsonPath("$.errors[0].message", containsString("publicTasksAggregate")))
                .expect(jsonPath("$.data").doesNotExist());
    }

    private HttpAnswer as(String role, String query) throws Exception {
        return http.post("/" + PROJECT + "/graphql", MAPPER.writeValueAsString(Map.of("query", query)), "Content-Type", "application/json", "Authorization", "Bearer " + token(role));
    }

    private static String token(String role) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("u@test.com").claim("userId", USER_ID)
                .claim("projectId", PROJECT).audience("excalibase:" + PROJECT)
                .claim("role", role).issuer("excalibase")
                .issueTime(Date.from(Instant.parse("2024-01-01T00:00:00Z")))
                .expirationTime(Date.from(Instant.parse("2099-01-01T00:00:00Z")))
                .build();
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), claims);
        signed.sign(new ECDSASigner(privateKey));
        return signed.serialize();
    }
}
