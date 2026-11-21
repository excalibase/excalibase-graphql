package io.github.excalibase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.HttpAnswer;
import io.github.excalibase.support.SqliteFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.github.excalibase.PermissionDocs.OWNED;
import static io.github.excalibase.support.Expect.jsonPath;
import static io.github.excalibase.support.Expect.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * A SQLite project with permissions on: SQLite has no roles and no row security, so the engine compiles every
 * permission into the SQL it sends. Reads, writes and read-backs are guarded exactly as on MySQL and Postgres, through
 * GraphQL and REST; the connecting file sees every row without it.
 */
class SqlitePermissionsIntegrationTest extends EngineIntegrationTest {

    private static final String ALICE = "11111111-1111-1111-1111-111111111111";
    private static final String BOB = "22222222-2222-2222-2222-222222222222";
    private static final String PROJECT = "proj-sqlite";
    private static final String ROLE = "app_authenticated";
    private static final String NOT_LOCKED = "{\"status\":{\"_neq\":\"locked\"}}";

    private static final SqliteFile DATABASE = SqliteFile.create("""
            CREATE TABLE notes (id INTEGER PRIMARY KEY, owner_id UUID, title TEXT, status TEXT NOT NULL DEFAULT 'open',
                pinned BOOLEAN NOT NULL DEFAULT 0);
            CREATE TABLE feedback (id INTEGER PRIMARY KEY, body TEXT);
            CREATE TABLE secrets (id INTEGER PRIMARY KEY, body TEXT);
            INSERT INTO notes VALUES (1, '11111111-1111-1111-1111-111111111111', 'alice-1', 'open', 0),
                (2, '22222222-2222-2222-2222-222222222222', 'bob-1', 'open', 0),
                (3, '11111111-1111-1111-1111-111111111111', 'alice-hidden', 'hidden', 0);
            INSERT INTO secrets VALUES (1, 'nobody may read this');
            """);

    private static final ECPrivateKey PRIVATE_KEY;
    private static final HttpServer CONTROL_PLANE;

    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair keys = generator.generateKeyPair();
            PRIVATE_KEY = (ECPrivateKey) keys.getPrivate();
            CONTROL_PLANE = HttpServer.create(new InetSocketAddress(0), 0);
            String jwks = jwks((ECPublicKey) keys.getPublic());
            CONTROL_PLANE.createContext("/.well-known/jwks.json", exchange -> {
                byte[] bytes = jwks.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            PermissionDocs.serve(CONTROL_PLANE, PROJECT, SqlitePermissionsIntegrationTest::permissions);
            CONTROL_PLANE.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stop() {
        CONTROL_PLANE.stop(0);
    }

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = DATABASE.engine(PROJECT);
        String control = "http://localhost:" + CONTROL_PLANE.getAddress().getPort();
        properties.put("app.security.jwt-enabled", "true");
        properties.put("app.security.insecure-dev-mode", "false");
        properties.put("app.security.auth.jwks-url", control + "/.well-known/jwks.json");
        properties.put("app.security.rls.policy-url", control + "/api");
        properties.put("app.security.rls.policy-pat", "test-pat");
        properties.put("app.security.rls.policy-ttl-ms", "0");
        return properties;
    }

    private static String permissions() {
        String readable = "{\"_and\":[" + OWNED + ",{\"status\":{\"_neq\":\"hidden\"}}]}";
        return PermissionDocs.document(PROJECT,
                PermissionDocs.entry("main.notes", ROLE, PermissionDocs.select(readable, "\"*\""),
                        PermissionDocs.insert(NOT_LOCKED, "[\"id\",\"title\",\"status\",\"pinned\"]",
                                "{\"owner_id\":\"X-Excalibase-User-Id\"}"),
                        PermissionDocs.update(OWNED, NOT_LOCKED, "[\"id\",\"title\",\"status\"]", "{}"),
                        PermissionDocs.delete(OWNED)),
                PermissionDocs.entry("main.feedback", ROLE,
                        PermissionDocs.insert("{\"body\":{\"_neq\":\"spam\"}}", "[\"body\"]", "{}")));
    }

    private final ObjectMapper mapper = new ObjectMapper();

    private JdbcTemplate database() {
        return new JdbcTemplate(new DriverManagerDataSource(DATABASE.url()));
    }

    private HttpAnswer as(String userId, String operation) throws Exception {
        return http.post("/" + PROJECT + "/graphql", mapper.writeValueAsString(Map.of("query", operation)),
                "Authorization", "Bearer " + jwt(userId), "Content-Type", "application/json");
    }

    private HttpAnswer rest(String userId, String method, String path, String body) throws Exception {
        return http.send(method, path, body, "Authorization", "Bearer " + jwt(userId),
                "Prefer", "return=representation", "Content-Type", "application/json");
    }

    @Test
    void authenticatedReadsAndWrites_shareTheFile_withoutLockErrors() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Integer>> outcomes = new ArrayList<>();
        for (int writer = 0; writer < 4; writer++) {
            int id = writer;
            outcomes.add(pool.submit(() -> failures(10, n -> as(ALICE, "mutation { createMainNotes(input: { id: "
                    + (5000 + id * 100 + n) + ", title: \"w" + id + "\" }) { id } }"))));
        }
        for (int reader = 0; reader < 4; reader++) {
            outcomes.add(pool.submit(() -> failures(25, n -> as(BOB, "{ mainNotes { id title } }"))));
        }
        int failed = 0;
        for (Future<Integer> outcome : outcomes) {
            failed += outcome.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(failed).as("requests that failed").isZero();
        assertThat(database().queryForObject("SELECT count(*) FROM notes WHERE id >= 5000", Integer.class)).isEqualTo(40);
    }

    private interface Request {
        HttpAnswer send(int n) throws Exception;
    }

    private static int failures(int count, Request request) {
        int failed = 0;
        for (int n = 0; n < count; n++) {
            try {
                HttpAnswer answer = request.send(n);
                if (answer.status() != 200 || answer.json().has("errors")) {
                    failed++;
                }
            } catch (Exception _) {
                failed++;
            }
        }
        return failed;
    }

    @Test
    void onConflict_neverOverwritesARowTheUpdateFilterHides() throws Exception {
        database().update("INSERT INTO notes (id, owner_id, title, status) VALUES (185, ?, 'bobs', 'open')", BOB);
        database().update("INSERT INTO notes (id, owner_id, title, status) VALUES (186, ?, 'alices', 'open')", ALICE);

        as(ALICE, "mutation { createMainNotes(input: { id: 185, title: \"hijacked\" },"
                + " onConflict: { constraint: \"PRIMARY\", update_columns: [title] }) { id } }").expect(status().isOk());
        as(ALICE, "mutation { createMainNotes(input: { id: 186, title: \"renamed\" },"
                + " onConflict: { constraint: \"PRIMARY\", update_columns: [title] }) { id title } }")
                .expect(jsonPath("$.data.createMainNotes.title").value("renamed"));

        assertThat(database().queryForObject("SELECT title FROM notes WHERE id = 185", String.class)).isEqualTo("bobs");
        assertThat(database().queryForObject("SELECT owner_id FROM notes WHERE id = 185", String.class)).isEqualTo(BOB);
        assertThat(database().queryForObject("SELECT title FROM notes WHERE id = 186", String.class)).isEqualTo("renamed");
    }

    @Test
    void select_readsOnlyTheRowsTheFilterKeeps() throws Exception {
        as(ALICE, "{ mainNotes(orderBy: { id: ASC }) { id title } }")
                .expect(status().isOk())
                .expect(jsonPath("$.data.mainNotes[*].title", hasItem("alice-1")))
                .expect(jsonPath("$.data.mainNotes[*].title", not(hasItem("bob-1"))))
                .expect(jsonPath("$.data.mainNotes[*].title", not(hasItem("alice-hidden"))));
        as(BOB, "{ mainNotes { title } }").expect(jsonPath("$.data.mainNotes[*].title", hasItem("bob-1")))
                .expect(jsonPath("$.data.mainNotes[*].title", not(hasItem("alice-1"))));
    }

    @Test
    void aTableWithoutAPermission_isNotInTheSchema_neverServedUnprotected() throws Exception {
        as(ALICE, "{ mainSecrets { id body } }").expect(jsonPath("$.errors[0].message").exists());
        rest(ALICE, "GET", "/" + PROJECT + "/api/v1/secrets", "").expect(status().is4xxClientError());
    }

    @Test
    void aRequestWithoutAToken_isRefused() {
        http.post("/" + PROJECT + "/graphql", "{\"query\":\"{ mainNotes { id } }\"}", "Content-Type", "application/json")
                .expect(jsonPath("$.data").doesNotExist());
        http.get("/" + PROJECT + "/api/v1/notes").expect(status().is4xxClientError());
    }

    @Test
    void insert_presetsTheOwner_andAnswersTheWrittenRow() throws Exception {
        as(ALICE, "mutation { createMainNotes(input: { title: \"preset\", pinned: true }) { title owner_id pinned } }")
                .expect(status().isOk())
                .expect(jsonPath("$.data.createMainNotes.title").value("preset"))
                .expect(jsonPath("$.data.createMainNotes.owner_id").value(ALICE))
                .expect(jsonPath("$.data.createMainNotes.pinned").value(true));
    }

    @Test
    void insert_failingItsCheck_isRefused_andWritesNothing() throws Exception {
        as(ALICE, "mutation { createMainNotes(input: { title: \"locked-insert\", status: \"locked\" }) { id } }")
                .expect(jsonPath("$.errors[0].extensions.code").value("permission_check_failed"));
        assertThat(count("title = 'locked-insert'")).isZero();
    }

    @Test
    void insert_ofARowTheRoleCannotRead_answersNull_butIsWritten() throws Exception {
        as(ALICE, "mutation { createMainNotes(input: { title: \"hidden-insert\", status: \"hidden\" }) { id } }")
                .expect(jsonPath("$.data.createMainNotes").value(nullValue()));
        assertThat(count("title = 'hidden-insert'")).isOne();
    }

    @Test
    void createMany_withOneRowFailingItsCheck_writesNoRow() throws Exception {
        as(ALICE, "mutation { createManyMainNotes(inputs: [{ title: \"many-1\" }, { title: \"many-2\", status: \"locked\" }]) { id } }")
                .expect(jsonPath("$.errors[0].extensions.code").value("permission_check_failed"));
        assertThat(count("title IN ('many-1', 'many-2')")).isZero();
    }

    @Test
    void update_ofAnotherOwnersRow_changesNothing() throws Exception {
        as(ALICE, "mutation { updateMainNotes(where: { id: { eq: 2 } }, input: { title: \"hacked\" }) { id } }")
                .expect(jsonPath("$.data.updateMainNotes", hasSize(0)));
        assertThat(count("id = 2 AND title = 'bob-1'")).isOne();
    }

    @Test
    void update_failingItsCheck_rollsBack() throws Exception {
        insertNote(50, ALICE, "check-me", "open");

        as(ALICE, "mutation { updateMainNotes(where: { id: { eq: 50 } }, input: { status: \"locked\" }) { id } }")
                .expect(jsonPath("$.errors[0].extensions.code").value("permission_check_failed"));
        assertThat(count("id = 50 AND status = 'open'")).isOne();
    }

    @Test
    void delete_removesOnlyPermittedRows_andAnswersThem() throws Exception {
        insertNote(70, ALICE, "mine", "open");
        insertNote(71, BOB, "theirs", "open");

        as(ALICE, "mutation { deleteMainNotes(where: { id: { in: [70, 71] } }) { id title } }")
                .expect(jsonPath("$.data.deleteMainNotes[*].title", contains("mine")));
        assertThat(count("id = 70")).isZero();
        assertThat(count("id = 71")).isOne();
    }

    @Test
    void insertOnly_answersTheCount_andStillChecksEveryRow() throws Exception {
        as(ALICE, "mutation { createManyMainFeedback(inputs: [{ body: \"nice\" }, { body: \"fine\" }]) { affected_rows } }")
                .expect(jsonPath("$.data.createManyMainFeedback.affected_rows").value(2));
        as(ALICE, "mutation { createMainFeedback(input: { body: \"spam\" }) { affected_rows } }")
                .expect(jsonPath("$.errors[0].extensions.code").value("permission_check_failed"));
        assertThat(database().queryForObject("SELECT count(*) FROM feedback WHERE body = 'spam'", Integer.class)).isZero();
    }

    @Test
    void restInsert_presetsTheOwner_andFailsItsCheckLoudly() throws Exception {
        rest(ALICE, "POST", "/" + PROJECT + "/api/v1/notes", "{\"title\":\"rest-ok\"}")
                .expect(status().isCreated())
                .expect(jsonPath("$.data.owner_id").value(ALICE));
        rest(ALICE, "POST", "/" + PROJECT + "/api/v1/notes", "{\"title\":\"rest-locked\",\"status\":\"locked\"}")
                .expect(status().isForbidden())
                .expect(jsonPath("$.code").value("permission_check_failed"));
        assertThat(count("title = 'rest-locked'")).isZero();
    }

    @Test
    void restDelete_removesOnlyTheCallersRows() throws Exception {
        insertNote(90, ALICE, "rest-mine", "open");
        insertNote(91, BOB, "rest-theirs", "open");

        rest(ALICE, "DELETE", "/" + PROJECT + "/api/v1/notes?id=in.(90,91)", "")
                .expect(jsonPath("$.data[*].title", contains("rest-mine")));
        assertThat(count("id = 91")).isOne();
    }

    @Test
    void restUpsert_neverOverwritesARowTheUpdateFilterHides() throws Exception {
        insertNote(85, BOB, "bobs", "open");
        insertNote(86, ALICE, "alices", "open");

        http.send("POST", "/" + PROJECT + "/api/v1/notes", "{\"id\":85,\"title\":\"hijacked\"}", "Authorization",
                "Bearer " + jwt(ALICE), "Prefer", "return=representation, resolution=merge-duplicates",
                "Content-Type", "application/json");
        http.send("POST", "/" + PROJECT + "/api/v1/notes", "{\"id\":86,\"title\":\"renamed\"}", "Authorization",
                "Bearer " + jwt(ALICE), "Prefer", "return=representation, resolution=merge-duplicates",
                "Content-Type", "application/json");
        assertThat(count("id = 85 AND title = 'bobs'")).isOne();
        assertThat(count("id = 86 AND title = 'renamed'")).isOne();
    }

    @Test
    void subscriptions_areRefusedWithAReason() throws Exception {
        as(ALICE, "subscription { mainNotesChanges { operation } }")
                .expect(jsonPath("$.errors[0].message").exists());
        as(ALICE, "{ __schema { subscriptionType { name } } }")
                .expect(jsonPath("$.data.__schema.subscriptionType").value(nullValue()));
    }

    private void insertNote(int id, String owner, String title, String noteStatus) {
        database().update("INSERT INTO notes (id, owner_id, title, status) VALUES (?, ?, ?, ?)", id, owner, title,
                noteStatus);
    }

    private int count(String condition) {
        return database().queryForObject("SELECT count(*) FROM notes WHERE " + condition, Integer.class);
    }

    private static String jwt(String userId) throws Exception {
        return jwt(userId, null);
    }

    /** With an aal claim; null leaves it out. */
    private static String jwt(String userId, String aal) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("u@test.com").claim("userId", userId).claim("projectId", PROJECT).claim("aal", aal)
                .audience("excalibase:" + PROJECT).claim("role", ROLE).issuer("excalibase")
                .issueTime(Date.from(Instant.parse("2024-01-01T00:00:00Z")))
                .expirationTime(Date.from(Instant.parse("2099-01-01T00:00:00Z")))
                .build();
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), claims);
        signed.sign(new ECDSASigner(PRIVATE_KEY));
        return signed.serialize();
    }

    private static String jwks(ECPublicKey key) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return "{\"keys\":[{\"kty\":\"EC\",\"crv\":\"P-256\",\"alg\":\"ES256\",\"use\":\"sig\",\"x\":\""
                + encoder.encodeToString(unsigned(key.getW().getAffineX().toByteArray())) + "\",\"y\":\""
                + encoder.encodeToString(unsigned(key.getW().getAffineY().toByteArray())) + "\"}]}";
    }

    /** A P-256 coordinate as exactly 32 bytes. */
    private static byte[] unsigned(byte[] coordinate) {
        byte[] fixed = new byte[32];
        int length = Math.min(coordinate.length, 32);
        System.arraycopy(coordinate, coordinate.length - length, fixed, 32 - length, length);
        return fixed;
    }
}
