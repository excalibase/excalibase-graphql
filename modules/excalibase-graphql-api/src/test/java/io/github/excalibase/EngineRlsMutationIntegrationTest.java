package io.github.excalibase;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.not;
import static io.github.excalibase.support.Expect.jsonPath;
import static io.github.excalibase.support.Expect.status;

/**
 * Engine-driven RLS on mutations — EXC-314. UPDATE/DELETE get an
 * operation-scoped WHERE filter (you can only mutate rows a policy allows),
 * and INSERT is gated by a RowMatcher WITH-CHECK (you cannot create a row a
 * policy forbids). Uses a dedicated writable table so mutation state never
 * affects the read-path tests.
 */
class EngineRlsMutationIntegrationTest extends EngineIntegrationTest {

    private static final String ALICE = "11111111-1111-1111-1111-111111111111";
    private static final String BOB = "22222222-2222-2222-2222-222222222222";
    private static final String PROJECT = "proj-rls";

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-engine-rls.sql");

    static ECPrivateKey privateKey;
    static ECPublicKey publicKey;
    static HttpServer mockVault;
    static int mockVaultPort;

    /** The document served now; each change bumps its version so the engines are rebuilt. */
    private static final AtomicReference<String> PERMISSIONS = new AtomicReference<>();
    private static final AtomicLong VERSION = new AtomicLong();

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
            PermissionDocs.serve(mockVault, PROJECT, PERMISSIONS::get);
            mockVault.start();
        } catch (Exception e) {
            throw new RuntimeException("setup failed", e);
        }
    }

    @AfterAll
    static void teardown() {
        if (mockVault != null) mockVault.stop(0);
    }

    @Override
    public Map<String, String> getProperties() {
        postgres.start();
        Map<String, String> properties = EngineProperties.isolated();
        properties.put("spring.datasource.url", postgres.getJdbcUrl());
        properties.put("spring.datasource.username", postgres.getUsername());
        properties.put("spring.datasource.password", postgres.getPassword());
        properties.put("app.database-type", "postgres");
        properties.put("app.max-rows", String.valueOf(30));
        properties.put("app.security.jwt-enabled", "true");
        properties.put("app.project-id", String.valueOf(PROJECT));
        properties.put("app.security.auth.jwks-url", String.valueOf("http://localhost:" + mockVaultPort + "/.well-known/jwks.json"));
        properties.put("app.security.rls.policy-url", String.valueOf("http://localhost:" + mockVaultPort + "/api"));
        properties.put("app.security.rls.policy-pat", "test-pat");
        properties.put("app.security.rls.policy-ttl-ms", String.valueOf(0));
        return properties;
    }


    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String ROLE = "app_authenticated";
    private static final String ALL = "\"*\"";

    /** Owner permission on the writable notes table, all operations. */
    private static String ownerNotes() {
        return PermissionDocs.entry("rls_demo.notes", ROLE, PermissionDocs.select(PermissionDocs.OWNED, ALL),
                PermissionDocs.insert(PermissionDocs.OWNED, ALL, "{}"),
                PermissionDocs.update(PermissionDocs.OWNED, PermissionDocs.OWNED, ALL, "{}"),
                PermissionDocs.delete(PermissionDocs.OWNED));
    }

    private static String open(String table) {
        return PermissionDocs.entry(table, ROLE, PermissionDocs.everything());
    }

    /** Owner permission on the child (book) table — used for nested-insert checks. */
    private static String ownerBook() {
        return PermissionDocs.entry("rls_demo.book", ROLE, PermissionDocs.select(PermissionDocs.OWNED, ALL),
                PermissionDocs.insert(PermissionDocs.OWNED, ALL, "{}"),
                PermissionDocs.update(PermissionDocs.OWNED, PermissionDocs.OWNED, ALL, "{}"),
                PermissionDocs.delete(PermissionDocs.OWNED));
    }

    /** Only shelf 999 is readable, so a book's forward embed to shelf 1 must come back null. */
    private static String onlyShelf999() {
        return PermissionDocs.entry("rls_demo.shelf", ROLE,
                PermissionDocs.select("{\"id\":{\"_eq\":999}}", ALL));
    }

    private static void permit(String... entries) {
        PERMISSIONS.set(PermissionDocs.document(PROJECT, VERSION.incrementAndGet(), entries));
    }

    @BeforeEach
    void seed() {
        permit(ownerNotes(), open("rls_demo.shelf"), open("rls_demo.book"));
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

    private HttpAnswer mutate(String userId, String mutation) throws Exception {
        return http.post("/" + PROJECT + "/graphql", body(mutation), "Authorization", "Bearer " + jwt(userId), "Content-Type", "application/json");
    }

    // ---- UPDATE ----

    @Test
    void update_ownRow_succeeds() throws Exception {
        mutate(ALICE, "mutation { updateRlsDemoNotes(where: { id: { eq: 1 } }, "
                + "input: { title: \"alice-edited\" }) { id title } }")
                .expect(status().isOk())
                .expect(jsonPath("$.data.updateRlsDemoNotes", hasSize(1)))
                .expect(jsonPath("$.data.updateRlsDemoNotes[0].title").value("alice-edited"));
    }

    @Test
    void update_othersRow_affectsNothing() throws Exception {
        // Alice targets Bob's note (id=2): the UPDATE policy's WHERE excludes it,
        // so zero rows change — RLS, not just a missing filter.
        mutate(ALICE, "mutation { updateRlsDemoNotes(where: { id: { eq: 2 } }, "
                + "input: { title: \"hacked\" }) { id } }")
                .expect(status().isOk())
                .expect(jsonPath("$.data.updateRlsDemoNotes", hasSize(0)));
    }

    // ---- DELETE ----

    @Test
    void delete_othersRow_affectsNothing() throws Exception {
        mutate(ALICE, "mutation { deleteRlsDemoNotes(where: { id: { eq: 2 } }) { id } }")
                .expect(status().isOk())
                .expect(jsonPath("$.data.deleteRlsDemoNotes", hasSize(0)));
    }

    // ---- INSERT WITH-CHECK ----

    @Test
    void insert_ownRow_succeeds() throws Exception {
        mutate(ALICE, "mutation { createRlsDemoNotes(input: { id: 100, "
                + "owner_id: \"" + ALICE + "\", title: \"new\" }) { id owner_id } }")
                .expect(status().isOk())
                .expect(jsonPath("$.data.createRlsDemoNotes.id").value("100"));
    }

    @Test
    void insert_rowForAnotherOwner_isRejected() throws Exception {
        // WITH-CHECK: Alice cannot create a note owned by Bob → policy violation,
        // surfaced as a typed permission_check_failed error, and no row is written.
        mutate(ALICE, "mutation { createRlsDemoNotes(input: { id: 101, "
                + "owner_id: \"" + BOB + "\", title: \"sneaky\" }) { id } }")
                .expect(status().isOk())
                .expect(jsonPath("$.errors", hasSize(1)))
                .expect(jsonPath("$.errors[0].extensions.code").value("permission_check_failed"))
                .expect(jsonPath("$.errors[0].message", not(containsString("ERROR:"))))
                .expect(jsonPath("$.errors[0].message", not(containsString("SQLSTATE"))))
                .expect(jsonPath("$.errors[0].message", not(containsString("violates"))))
                .expect(jsonPath("$.data.createRlsDemoNotes").doesNotExist());
        mutate(BOB, "{ rlsDemoNotes(where: { id: { eq: 101 } }) { id } }")
                .expect(jsonPath("$.data.rlsDemoNotes", hasSize(0)));
    }

    @Test
    void update_reassignToAnotherOwner_failsTheCheck() throws Exception {
        // WITH-CHECK on the new image: Alice cannot hand her note to Bob.
        mutate(ALICE, "mutation { updateRlsDemoNotes(where: { id: { eq: 1 } }, "
                + "input: { owner_id: \"" + BOB + "\" }) { id } }")
                .expect(status().isOk())
                .expect(jsonPath("$.errors", hasSize(1)))
                .expect(jsonPath("$.errors[0].extensions.code").value("permission_check_failed"))
                .expect(jsonPath("$.errors[0].message", not(containsString("ERROR:"))))
                .expect(jsonPath("$.errors[0].message", not(containsString("violates"))))
                .expect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void upsert_rowForAnotherOwner_failsTheCheck() throws Exception {
        // The candidate row of an upsert still owes the insert check.
        mutate(ALICE, "mutation { createRlsDemoNotes("
                + "input: { id: 102, owner_id: \"" + BOB + "\", title: \"sneaky\" }, "
                + "onConflict: { constraint: \"notes_pkey\", update_columns: [\"title\"] }) { id } }")
                .expect(status().isOk())
                .expect(jsonPath("$.errors[0].extensions.code").value("permission_check_failed"));
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
                .expect(status().isOk())
                .expect(jsonPath("$.errors").doesNotExist());
        // Bob still sees his original note title — Alice's upsert did not touch it.
        mutate(BOB, "{ rlsDemoNotes(where: { id: { eq: 2 } }) { id title } }")
                .expect(status().isOk())
                .expect(jsonPath("$.data.rlsDemoNotes", hasSize(1)))
                .expect(jsonPath("$.data.rlsDemoNotes[0].title").value("bob-note"));
    }

    @Test
    void upsert_ownRow_updatesTitle() throws Exception {
        // Alice upserts on id=1 (her own note): the USING predicate matches, so the
        // conflict path updates her row — the gate must not over-block legitimate upserts.
        mutate(ALICE, "mutation { createRlsDemoNotes("
                + "input: { id: 1, owner_id: \"" + ALICE + "\", title: \"alice-upserted\" }, "
                + "onConflict: { constraint: \"notes_pkey\", update_columns: [\"title\"] }) { id } }")
                .expect(status().isOk())
                .expect(jsonPath("$.errors").doesNotExist());
        mutate(ALICE, "{ rlsDemoNotes(where: { id: { eq: 1 } }) { id title } }")
                .expect(status().isOk())
                .expect(jsonPath("$.data.rlsDemoNotes[0].title").value("alice-upserted"));
    }

    // ---- NESTED-FK CHILD INSERT WITH-CHECK (audit H5) ----

    @Test
    void nestedInsert_childRowForAnotherOwner_isRejected() throws Exception {
        // Alice creates a shelf with a nested book owned by Bob. The child table's
        // WITH-CHECK (owner policy on rls_demo.book) must reject the whole mutation —
        // a nested insert is not a hole around top-level WITH-CHECK.
        permit(ownerNotes(), open("rls_demo.shelf"), ownerBook());
        mutate(ALICE, "mutation { createRlsDemoShelf(input: { id: 500, name: \"s\", "
                + "rlsDemoBook: { data: [ { id: 900, owner_id: \"" + BOB + "\", title: \"x\" } ] } }) { id } }")
                .expect(status().isOk())
                .expect(jsonPath("$.errors[0].extensions.code").value("permission_check_failed"))
                .expect(jsonPath("$.data.createRlsDemoShelf").doesNotExist());
        mutate(ALICE, "{ rlsDemoShelf(where: { id: { eq: 500 } }) { id } }")
                .expect(jsonPath("$.data.rlsDemoShelf", hasSize(0)));
    }

    @Test
    void nestedInsert_childRowOwnedBySelf_succeeds() throws Exception {
        // Alice creates a shelf with a nested book she owns: the child WITH-CHECK
        // passes, and the child CTE casts the uuid owner_id param (regression for the
        // nested-insert type-cast bug — a bare bind is varchar in INSERT ... SELECT).
        permit(ownerNotes(), open("rls_demo.shelf"), ownerBook());
        mutate(ALICE, "mutation { createRlsDemoShelf(input: { id: 501, name: \"s2\", "
                + "rlsDemoBook: { data: [ { id: 901, owner_id: \"" + ALICE + "\", title: \"ok\" } ] } }) { id } }")
                .expect(status().isOk())
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.createRlsDemoShelf.id").value("501"));
    }

    // ---- MUTATION RESULT EMBEDS (GQL-01) ----

    @Test
    void mutationResult_reverseEmbed_isFilteredLikeQuery() throws Exception {
        permit(ownerNotes(), open("rls_demo.shelf"), ownerBook());
        mutate(BOB, "{ rlsDemoShelf(where: { id: { eq: 1 } }) { id rlsDemoBook { id } } }")
                .expect(jsonPath("$.data.rlsDemoShelf[0].rlsDemoBook", hasSize(1)));

        mutate(BOB, "mutation { updateRlsDemoShelf(where: { id: { eq: 1 } }, "
                + "input: { name: \"main\" }) { id rlsDemoBook { id title } } }")
                .expect(status().isOk())
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.updateRlsDemoShelf[0].rlsDemoBook", hasSize(1)))
                .expect(jsonPath("$.data.updateRlsDemoShelf[0].rlsDemoBook[0].title").value("bob-book-1"));
    }

    @Test
    void mutationResult_forwardEmbed_isFilteredLikeQuery() throws Exception {
        permit(ownerNotes(), onlyShelf999(), ownerBook());
        mutate(ALICE, "{ rlsDemoBook(where: { id: { eq: 10 } }) { id rlsDemoShelfId { id name } } }")
                .expect(jsonPath("$.data.rlsDemoBook[0].rlsDemoShelfId").doesNotExist());

        mutate(ALICE, "mutation { createRlsDemoBook(input: { id: 950, shelf_id: 1, "
                + "owner_id: \"" + ALICE + "\", title: \"embed\" }) { id rlsDemoShelfId { id name } } }")
                .expect(status().isOk())
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.createRlsDemoBook.id").value("950"))
                .expect(jsonPath("$.data.createRlsDemoBook.rlsDemoShelfId").doesNotExist());
    }

    // ---- PROCEDURES: never exposed, whatever the role (spec §6) ----

    @Test
    void aProcedure_isReachableByNoRole() throws Exception {
        mutate(BOB, "mutation { createRlsDemoNotes(input: { id: 410, "
                + "owner_id: \"" + BOB + "\", title: \"bob-410\" }) { id } }").expect(status().isOk());

        mutate(BOB, "mutation { callRlsDemoRenameNote(p_id: 410, p_title: \"renamed\") }")
                .expect(status().isOk())
                .expect(jsonPath("$.data.callRlsDemoRenameNote").doesNotExist());
        http.post("/" + PROJECT + "/graphql", body("mutation { callRlsDemoRenameNote(p_id: 410, p_title: \"anon-renamed\") }"), "Content-Type", "application/json")
                .expect(jsonPath("$.data.callRlsDemoRenameNote").doesNotExist());

        mutate(BOB, "{ rlsDemoNotes(where: { id: { eq: 410 } }) { title } }")
                .expect(jsonPath("$.data.rlsDemoNotes[0].title").value("bob-410"));
    }

    // ---- WHAT A MUTATION RETURNS (risk 38) ----

    @Test
    void mutationOutput_onlyHoldsRowsTheRoleMaySelect() throws Exception {
        // Alice may update every note but read only her own: the update reaches both of these,
        // but only hers comes back.
        permit(PermissionDocs.entry("rls_demo.notes", ROLE, PermissionDocs.select(PermissionDocs.OWNED, ALL),
                PermissionDocs.insert("{}", ALL, "{}"), PermissionDocs.update("{}", "{}", ALL, "{}")),
                open("rls_demo.shelf"), open("rls_demo.book"));
        mutate(ALICE, "mutation { a: createRlsDemoNotes(input: { id: 340, owner_id: \"" + ALICE
                + "\", title: \"a\" }) { id } b: createRlsDemoNotes(input: { id: 341, owner_id: \"" + BOB
                + "\", title: \"b\" }) { id } }")
                .expect(jsonPath("$.data.a.id").value("340"))
                .expect(jsonPath("$.data", hasKey("b")))
                .expect(jsonPath("$.data.b").value(nullValue()));
    }

    @Test
    void aWrittenRowTheRoleCannotRead_isReturnedAsNullOrAnEmptyList() throws Exception {
        // Alice may insert any note but read only her own: a note she plants for Bob is written,
        // and the field still answers — null for one row, an empty list for many.
        permit(PermissionDocs.entry("rls_demo.notes", ROLE, PermissionDocs.select(PermissionDocs.OWNED, ALL),
                PermissionDocs.insert("{}", ALL, "{}")), open("rls_demo.shelf"), open("rls_demo.book"));

        mutate(ALICE, "mutation { createRlsDemoNotes(input: { id: 360, owner_id: \"" + BOB
                + "\", title: \"planted\" }) { id title } }")
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data", hasKey("createRlsDemoNotes")))
                .expect(jsonPath("$.data.createRlsDemoNotes").value(nullValue()));
        mutate(ALICE, "mutation { createManyRlsDemoNotes(inputs: [{ id: 361, owner_id: \"" + BOB
                + "\", title: \"planted\" }]) { id } }")
                .expect(jsonPath("$.data.createManyRlsDemoNotes", hasSize(0)));
        http.post("/" + PROJECT + "/api/v1/notes", "{\"id\": 362, \"owner_id\": \"" + BOB + "\", \"title\": \"planted\"}", "Authorization", "Bearer " + jwt(ALICE), "Content-Profile", "rls_demo", "Prefer", "return=representation", "Content-Type", "application/json")
                .expect(status().isCreated())
                .expect(jsonPath("$.data", hasSize(0)));

        permit(ownerNotes(), open("rls_demo.shelf"), open("rls_demo.book"));
        mutate(BOB, "{ rlsDemoNotes(where: { id: { in: [360, 361, 362] } }) { id } }")
                .expect(jsonPath("$.data.rlsDemoNotes", hasSize(3)));
    }

    // ---- PRESETS ----

    @Test
    void presets_areWrittenAndCannotBeSetByTheClient() throws Exception {
        permit(PermissionDocs.entry("rls_demo.notes", ROLE, PermissionDocs.select(PermissionDocs.OWNED, ALL),
                PermissionDocs.insert("{}", "[\"id\",\"title\"]", "{\"owner_id\":\"X-Excalibase-User-Id\"}")),
                open("rls_demo.shelf"), open("rls_demo.book"));

        mutate(ALICE, "mutation { createRlsDemoNotes(input: { id: 350, title: \"preset\" }) { id owner_id } }")
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.createRlsDemoNotes.owner_id").value(ALICE));
        mutate(ALICE, "mutation { createRlsDemoNotes(input: { id: 351, owner_id: \"" + BOB
                + "\", title: \"x\" }) { id } }")
                .expect(jsonPath("$.errors[0].message", containsString("owner_id")));
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
                + "owner_id: \"" + ALICE + "\", title: \"a\" }) { id } }").expect(status().isOk());
        mutate(BOB, "mutation { createRlsDemoNotes(input: { id: 311, "
                + "owner_id: \"" + BOB + "\", title: \"b\" }) { id } }").expect(status().isOk());

        // Alice edits her own note and Bob's in one request: hers applies, his
        // matches no row. One request must not lift the filter for the other.
        mutate(ALICE, "mutation { mine: updateRlsDemoNotes(where: { id: { eq: 310 } }, "
                + "input: { title: \"mine-edited\" }) { id title } "
                + "theirs: updateRlsDemoNotes(where: { id: { eq: 311 } }, "
                + "input: { title: \"hijacked\" }) { id title } }")
                .expect(status().isOk())
                .expect(jsonPath("$.data.mine", hasSize(1)))
                .expect(jsonPath("$.data.mine[0].title").value("mine-edited"))
                .expect(jsonPath("$.data.theirs", hasSize(0)));

        // Bob's row is untouched.
        http.post("/" + PROJECT + "/graphql", body("{ rlsDemoNotes(where: { id: { eq: 311 } }) { title } }"), "Authorization", "Bearer " + jwt(BOB), "Content-Type", "application/json")
                .expect(jsonPath("$.data.rlsDemoNotes[0].title").value("b"));
    }

    @Test
    void multiMutation_allowedAndDeniedDelete_inOneRequest() throws Exception {
        mutate(ALICE, "mutation { createRlsDemoNotes(input: { id: 320, "
                + "owner_id: \"" + ALICE + "\", title: \"a\" }) { id } }").expect(status().isOk());
        mutate(BOB, "mutation { createRlsDemoNotes(input: { id: 321, "
                + "owner_id: \"" + BOB + "\", title: \"b\" }) { id } }").expect(status().isOk());

        mutate(ALICE, "mutation { mine: deleteRlsDemoNotes(where: { id: { eq: 320 } }) { id } "
                + "theirs: deleteRlsDemoNotes(where: { id: { eq: 321 } }) { id } }")
                .expect(status().isOk())
                .expect(jsonPath("$.data.mine", hasSize(1)))
                .expect(jsonPath("$.data.theirs", hasSize(0)));

        // Bob's row survived Alice's delete.
        http.post("/" + PROJECT + "/graphql", body("{ rlsDemoNotes(where: { id: { eq: 321 } }) { id } }"), "Authorization", "Bearer " + jwt(BOB), "Content-Type", "application/json")
                .expect(jsonPath("$.data.rlsDemoNotes", hasSize(1)));
    }

    @Test
    void multiMutation_deniedInsertDoesNotRideAlongWithAnAllowedOne() throws Exception {
        // The allowed insert must not carry the denied one past WITH-CHECK.
        mutate(ALICE, "mutation { ok: createRlsDemoNotes(input: { id: 330, "
                + "owner_id: \"" + ALICE + "\", title: \"ok\" }) { id } "
                + "bad: createRlsDemoNotes(input: { id: 331, "
                + "owner_id: \"" + BOB + "\", title: \"sneaky\" }) { id } }")
                .expect(status().isOk())
                .expect(jsonPath("$.errors[0].extensions.code").value("permission_check_failed"))
                .expect(jsonPath("$.data.bad").doesNotExist());

        // The denied row must not exist afterwards.
        http.post("/" + PROJECT + "/graphql", body("{ rlsDemoNotes(where: { id: { eq: 331 } }) { id } }"), "Authorization", "Bearer " + jwt(BOB), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.rlsDemoNotes", hasSize(0)));
    }
}
