package io.github.excalibase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.github.excalibase.rls.Assignment;
import io.github.excalibase.rls.FieldType;
import io.github.excalibase.rls.InMemoryPolicyProvider;
import io.github.excalibase.rls.TableGrant;
import io.github.excalibase.rls.LogicOperator;
import io.github.excalibase.rls.Operation;
import io.github.excalibase.rls.Policy;
import io.github.excalibase.rls.PolicyEffect;
import io.github.excalibase.rls.PolicyProvider;
import io.github.excalibase.rls.Rule;
import io.github.excalibase.rls.RuleOperator;
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
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Engine-driven RLS on mutations — EXC-314. UPDATE/DELETE get an
 * operation-scoped WHERE filter (you can only mutate rows a policy allows),
 * and INSERT is gated by a RowMatcher WITH-CHECK (you cannot create a row a
 * policy forbids). Uses a dedicated writable table so mutation state never
 * affects the read-path tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class EngineRlsMutationIntegrationTest {

    private static final String ALICE = "11111111-1111-1111-1111-111111111111";
    private static final String BOB = "22222222-2222-2222-2222-222222222222";
    private static final String PROJECT = "proj-rls";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-engine-rls.sql");

    static ECPrivateKey privateKey;
    static ECPublicKey publicKey;
    static HttpServer mockVault;
    static int mockVaultPort;

    static {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair kp = gen.generateKeyPair();
            privateKey = (ECPrivateKey) kp.getPrivate();
            publicKey = (ECPublicKey) kp.getPublic();
            mockVault = HttpServer.create(new InetSocketAddress(0), 0);
            mockVaultPort = mockVault.getAddress().getPort();
            String jwksJson = buildJwks(publicKey);
            mockVault.createContext("/.well-known/jwks.json", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                byte[] b = jwksJson.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, b.length);
                exchange.getResponseBody().write(b);
                exchange.getResponseBody().close();
            });
            mockVault.start();
        } catch (Exception e) {
            throw new RuntimeException("setup failed", e);
        }
    }

    @AfterAll
    static void teardown() {
        if (mockVault != null) mockVault.stop(0);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.database-type", () -> "postgres");
        registry.add("app.max-rows", () -> 30);
        registry.add("app.security.jwt-enabled", () -> "true");
        registry.add("app.security.auth.jwks-url",
                () -> "http://localhost:" + mockVaultPort + "/.well-known/jwks.json");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private PolicyProvider policyProvider;
    private static final ObjectMapper mapper = new ObjectMapper();

    /** Owner policy on the writable notes table, all operations. */
    private static Policy ownerAll() {
        return new Policy("owner-notes", "owner-notes", "rls_demo.notes",
                PolicyEffect.ALLOW, Operation.ALL, LogicOperator.AND, 0, true,
                List.of(new Rule("owner_id", FieldType.UUID, RuleOperator.EQ, "{{currentUserId}}")),
                List.of(Assignment.all()));
    }

    /** Owner policy on the child (book) table — used for nested-insert WITH-CHECK. */
    private static Policy ownerBook() {
        return new Policy("owner-book", "owner-book", "rls_demo.book",
                PolicyEffect.ALLOW, Operation.ALL, LogicOperator.AND, 0, true,
                List.of(new Rule("owner_id", FieldType.UUID, RuleOperator.EQ, "{{currentUserId}}")),
                List.of(Assignment.all()));
    }

    @BeforeEach
    void seed() {
        var provider = (InMemoryPolicyProvider) policyProvider;
        provider.put(PROJECT, List.of(ownerAll()));
        // Exposure is granted outright here: this suite tests the row policies
        // that run after the grant layer, not the grant layer itself.
        provider.putGrants(PROJECT, List.of(new TableGrant("grant-all", "grant-all",
                TableGrant.ALL_RESOURCES, Operation.ALL, List.of(Assignment.all()), true)));
    }

    private String body(String query) throws Exception {
        return mapper.writeValueAsString(Map.of("query", query));
    }

    private String jwt(String userId) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("u@test.com").claim("userId", userId).claim("projectId", PROJECT)
                .audience("excalibase:" + PROJECT)
                .claim("role", "app_authenticated").issuer("excalibase")
                .issueTime(java.util.Date.from(java.time.Instant.parse("2024-01-01T00:00:00Z"))).expirationTime(java.util.Date.from(java.time.Instant.parse("2099-01-01T00:00:00Z")))
                .build();
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), claims);
        signed.sign(new ECDSASigner(privateKey));
        return signed.serialize();
    }

    private org.springframework.test.web.servlet.ResultActions mutate(String userId, String mutation) throws Exception {
        return mockMvc.perform(post("/" + PROJECT + "/graphql")
                .header("Authorization", "Bearer " + jwt(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(mutation)));
    }

    // ---- UPDATE ----

    @Test
    void update_ownRow_succeeds() throws Exception {
        mutate(ALICE, "mutation { updateRlsDemoNotes(where: { id: { eq: 1 } }, "
                + "input: { title: \"alice-edited\" }) { id title } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updateRlsDemoNotes", hasSize(1)))
                .andExpect(jsonPath("$.data.updateRlsDemoNotes[0].title").value("alice-edited"));
    }

    @Test
    void update_othersRow_affectsNothing() throws Exception {
        // Alice targets Bob's note (id=2): the UPDATE policy's WHERE excludes it,
        // so zero rows change — RLS, not just a missing filter.
        mutate(ALICE, "mutation { updateRlsDemoNotes(where: { id: { eq: 2 } }, "
                + "input: { title: \"hacked\" }) { id } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updateRlsDemoNotes", hasSize(0)));
    }

    // ---- DELETE ----

    @Test
    void delete_othersRow_affectsNothing() throws Exception {
        mutate(ALICE, "mutation { deleteRlsDemoNotes(where: { id: { eq: 2 } }) { id } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleteRlsDemoNotes", hasSize(0)));
    }

    // ---- INSERT WITH-CHECK ----

    @Test
    void insert_ownRow_succeeds() throws Exception {
        mutate(ALICE, "mutation { createRlsDemoNotes(input: { id: 100, "
                + "owner_id: \"" + ALICE + "\", title: \"new\" }) { id owner_id } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createRlsDemoNotes.id").value("100"));
    }

    @Test
    void insert_rowForAnotherOwner_isRejected() throws Exception {
        // WITH-CHECK: Alice cannot create a note owned by Bob → policy violation,
        // surfaced as a typed RLS_DENIED GraphQL error, and no row is written.
        mutate(ALICE, "mutation { createRlsDemoNotes(input: { id: 101, "
                + "owner_id: \"" + BOB + "\", title: \"sneaky\" }) { id } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].extensions.code").value("RLS_DENIED"))
                .andExpect(jsonPath("$.errors[0].extensions.operation").value("INSERT"))
                .andExpect(jsonPath("$.errors[0].extensions.table").value("rls_demo.notes"))
                .andExpect(jsonPath("$.errors[0].message", not(containsString("ERROR:"))))
                .andExpect(jsonPath("$.errors[0].message", not(containsString("SQLSTATE"))))
                .andExpect(jsonPath("$.errors[0].message", not(containsString("violates"))))
                .andExpect(jsonPath("$.data.createRlsDemoNotes").doesNotExist());
    }

    @Test
    void update_reassignToAnotherOwner_returnsRlsDenied() throws Exception {
        // WITH-CHECK on the new image: Alice cannot hand her note to Bob.
        mutate(ALICE, "mutation { updateRlsDemoNotes(where: { id: { eq: 1 } }, "
                + "input: { owner_id: \"" + BOB + "\" }) { id } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].extensions.code").value("RLS_DENIED"))
                .andExpect(jsonPath("$.errors[0].extensions.operation").value("UPDATE"))
                .andExpect(jsonPath("$.errors[0].extensions.table").value("rls_demo.notes"))
                .andExpect(jsonPath("$.errors[0].message", not(containsString("ERROR:"))))
                .andExpect(jsonPath("$.errors[0].message", not(containsString("violates"))))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void upsert_rowForAnotherOwner_returnsRlsDeniedWithUpsertOperation() throws Exception {
        // The candidate row of an upsert is still an INSERT WITH-CHECK; the error
        // names the caller's operation (UPSERT) so clients can tell the two apart.
        mutate(ALICE, "mutation { createRlsDemoNotes("
                + "input: { id: 102, owner_id: \"" + BOB + "\", title: \"sneaky\" }, "
                + "onConflict: { constraint: \"notes_pkey\", update_columns: [\"title\"] }) { id } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.code").value("RLS_DENIED"))
                .andExpect(jsonPath("$.errors[0].extensions.operation").value("UPSERT"))
                .andExpect(jsonPath("$.errors[0].extensions.table").value("rls_demo.notes"));
    }

    // ---- UPSERT ON CONFLICT USING (audit H6) ----

    @Test
    void upsert_cannotOverwriteAnotherOwnersRow() throws Exception {
        // Alice upserts on id=2 (Bob's note). The ON CONFLICT DO UPDATE is gated by
        // the UPDATE USING predicate (notes.owner_id = alice), so Bob's row is excluded
        // and his title is not overwritten — an upsert is not an ownership bypass. The
        // statement itself must be valid SQL (no EXCLUDED ambiguity → no error).
        mutate(ALICE, "mutation { createRlsDemoNotes("
                + "input: { id: 2, owner_id: \"" + ALICE + "\", title: \"hijacked\" }, "
                + "onConflict: { constraint: \"notes_pkey\", update_columns: [\"title\"] }) { id } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").doesNotExist());
        // Bob still sees his original note title — Alice's upsert did not touch it.
        mutate(BOB, "{ rlsDemoNotes(where: { id: { eq: 2 } }) { id title } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoNotes", hasSize(1)))
                .andExpect(jsonPath("$.data.rlsDemoNotes[0].title").value("bob-note"));
    }

    @Test
    void upsert_ownRow_updatesTitle() throws Exception {
        // Alice upserts on id=1 (her own note): the USING predicate matches, so the
        // conflict path updates her row — the gate must not over-block legitimate upserts.
        mutate(ALICE, "mutation { createRlsDemoNotes("
                + "input: { id: 1, owner_id: \"" + ALICE + "\", title: \"alice-upserted\" }, "
                + "onConflict: { constraint: \"notes_pkey\", update_columns: [\"title\"] }) { id } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").doesNotExist());
        mutate(ALICE, "{ rlsDemoNotes(where: { id: { eq: 1 } }) { id title } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoNotes[0].title").value("alice-upserted"));
    }

    // ---- NESTED-FK CHILD INSERT WITH-CHECK (audit H5) ----

    @Test
    void nestedInsert_childRowForAnotherOwner_isRejected() throws Exception {
        // Alice creates a shelf with a nested book owned by Bob. The child table's
        // WITH-CHECK (owner policy on rls_demo.book) must reject the whole mutation —
        // a nested insert is not a hole around top-level WITH-CHECK.
        ((InMemoryPolicyProvider) policyProvider).put(PROJECT, List.of(ownerAll(), ownerBook()));
        mutate(ALICE, "mutation { createRlsDemoShelf(input: { id: 500, name: \"s\", "
                + "rlsDemoBook: { data: [ { id: 900, owner_id: \"" + BOB + "\", title: \"x\" } ] } }) { id } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.code").value("RLS_DENIED"))
                .andExpect(jsonPath("$.errors[0].extensions.operation").value("INSERT"))
                .andExpect(jsonPath("$.errors[0].extensions.table").value("rls_demo.book"))
                .andExpect(jsonPath("$.data.createRlsDemoShelf").doesNotExist());
    }

    @Test
    void nestedInsert_childRowOwnedBySelf_succeeds() throws Exception {
        // Alice creates a shelf with a nested book she owns: the child WITH-CHECK
        // passes, and the child CTE casts the uuid owner_id param (regression for the
        // nested-insert type-cast bug — a bare bind is varchar in INSERT ... SELECT).
        ((InMemoryPolicyProvider) policyProvider).put(PROJECT, List.of(ownerAll(), ownerBook()));
        mutate(ALICE, "mutation { createRlsDemoShelf(input: { id: 501, name: \"s2\", "
                + "rlsDemoBook: { data: [ { id: 901, owner_id: \"" + ALICE + "\", title: \"ok\" } ] } }) { id } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.createRlsDemoShelf.id").value("501"));
    }

    private static String buildJwks(ECPublicKey key) {
        com.nimbusds.jose.jwk.ECKey ecKey = new com.nimbusds.jose.jwk.ECKey.Builder(
                com.nimbusds.jose.jwk.Curve.P_256, key)
                .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE).keyID("test-key").build();
        return new com.nimbusds.jose.jwk.JWKSet(ecKey).toString();
    }

    // --- Multiple mutations in one request: each field enforced on its own. ---
    // Each test seeds its own ids; the writable table is shared across tests.

    @Test
    void multiMutation_allowedAndDeniedUpdate_inOneRequest() throws Exception {
        mutate(ALICE, "mutation { createRlsDemoNotes(input: { id: 310, "
                + "owner_id: \"" + ALICE + "\", title: \"a\" }) { id } }").andExpect(status().isOk());
        mutate(BOB, "mutation { createRlsDemoNotes(input: { id: 311, "
                + "owner_id: \"" + BOB + "\", title: \"b\" }) { id } }").andExpect(status().isOk());

        // Alice edits her own note and Bob's in one request: hers applies, his
        // matches no row. One request must not lift the filter for the other.
        mutate(ALICE, "mutation { mine: updateRlsDemoNotes(where: { id: { eq: 310 } }, "
                + "input: { title: \"mine-edited\" }) { id title } "
                + "theirs: updateRlsDemoNotes(where: { id: { eq: 311 } }, "
                + "input: { title: \"hijacked\" }) { id title } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mine", hasSize(1)))
                .andExpect(jsonPath("$.data.mine[0].title").value("mine-edited"))
                .andExpect(jsonPath("$.data.theirs", hasSize(0)));

        // Bob's row is untouched.
        mockMvc.perform(post("/" + PROJECT + "/graphql")
                        .header("Authorization", "Bearer " + jwt(BOB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoNotes(where: { id: { eq: 311 } }) { title } }")))
                .andExpect(jsonPath("$.data.rlsDemoNotes[0].title").value("b"));
    }

    @Test
    void multiMutation_allowedAndDeniedDelete_inOneRequest() throws Exception {
        mutate(ALICE, "mutation { createRlsDemoNotes(input: { id: 320, "
                + "owner_id: \"" + ALICE + "\", title: \"a\" }) { id } }").andExpect(status().isOk());
        mutate(BOB, "mutation { createRlsDemoNotes(input: { id: 321, "
                + "owner_id: \"" + BOB + "\", title: \"b\" }) { id } }").andExpect(status().isOk());

        mutate(ALICE, "mutation { mine: deleteRlsDemoNotes(where: { id: { eq: 320 } }) { id } "
                + "theirs: deleteRlsDemoNotes(where: { id: { eq: 321 } }) { id } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mine", hasSize(1)))
                .andExpect(jsonPath("$.data.theirs", hasSize(0)));

        // Bob's row survived Alice's delete.
        mockMvc.perform(post("/" + PROJECT + "/graphql")
                        .header("Authorization", "Bearer " + jwt(BOB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoNotes(where: { id: { eq: 321 } }) { id } }")))
                .andExpect(jsonPath("$.data.rlsDemoNotes", hasSize(1)));
    }

    @Test
    void multiMutation_deniedInsertDoesNotRideAlongWithAnAllowedOne() throws Exception {
        // The allowed insert must not carry the denied one past WITH-CHECK.
        mutate(ALICE, "mutation { ok: createRlsDemoNotes(input: { id: 330, "
                + "owner_id: \"" + ALICE + "\", title: \"ok\" }) { id } "
                + "bad: createRlsDemoNotes(input: { id: 331, "
                + "owner_id: \"" + BOB + "\", title: \"sneaky\" }) { id } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.code").value("RLS_DENIED"))
                .andExpect(jsonPath("$.data.bad").doesNotExist());

        // The denied row must not exist afterwards.
        mockMvc.perform(post("/" + PROJECT + "/graphql")
                        .header("Authorization", "Bearer " + jwt(BOB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoNotes(where: { id: { eq: 331 } }) { id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoNotes", hasSize(0)));
    }
}
