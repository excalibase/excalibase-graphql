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
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

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

import static io.github.excalibase.PermissionDocs.OWNED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static io.github.excalibase.support.Expect.jsonPath;
import static io.github.excalibase.support.Expect.status;

/**
 * A MySQL project with permissions on (EXC-618): the engine starts with a permission source and every
 * read and write is guarded as on Postgres. Writes on MySQL have no RETURNING, so the rows a mutation
 * writes are locked and keyed first; checks, presets and the select filter then apply to exactly those.
 */
class MysqlPermissionsIntegrationTest extends EngineIntegrationTest {

    private static final String ALICE = "11111111-1111-1111-1111-111111111111";
    private static final String BOB = "22222222-2222-2222-2222-222222222222";
    private static final String PROJECT = "proj-mysql";
    private static final String ROLE = "app_authenticated";
    private static final String NOT_LOCKED = "{\"status\":{\"_neq\":\"locked\"}}";

    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4").withInitScript("init-mysql-permissions.sql");

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
            PermissionDocs.serve(CONTROL_PLANE, PROJECT, MysqlPermissionsIntegrationTest::permissions);
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
        mysql.start();
        Map<String, String> properties = EngineProperties.isolated();
        properties.put("spring.datasource.url", mysql.getJdbcUrl());
        properties.put("spring.datasource.username", mysql.getUsername());
        properties.put("spring.datasource.password", mysql.getPassword());
        properties.put("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        properties.put("app.database-type", "mysql");
        properties.put("app.max-rows", String.valueOf(30));
        properties.put("app.project-id", String.valueOf(PROJECT));
        properties.put("app.security.jwt-enabled", "true");
        properties.put("app.security.auth.jwks-url", String.valueOf("http://localhost:" + CONTROL_PLANE.getAddress().getPort() + "/.well-known/jwks.json"));
        properties.put("app.security.rls.policy-url", String.valueOf("http://localhost:" + CONTROL_PLANE.getAddress().getPort() + "/api"));
        properties.put("app.security.rls.policy-pat", "test-pat");
        properties.put("app.security.rls.policy-ttl-ms", String.valueOf(0));
        return properties;
    }


    /** Studio and the MySQL loader name a table {@code <database>.<table>}: the database is its schema. */
    private static String table(String name) {
        return mysql.getDatabaseName() + "." + name;
    }

    private static String hasAGate() {
        return "{\"_exists\":{\"_table\":\"" + table("drafts") + "\",\"_where\":{\"_and\":[" + OWNED
                + ",{\"title\":{\"_eq\":\"gate\"}}]}}}";
    }

    private static String ownsABoard() {
        return "{\"_exists\":{\"_table\":\"" + table("boards") + "\",\"_where\":" + OWNED + "}}";
    }

    /**
     * Notes: a role reads its own rows that are not hidden, writes only rows that are not locked, and
     * every row it inserts is its own. Boards: writable by whoever owns some board. Feedback: insert only.
     */
    private static String permissions() {
        String readable = "{\"_and\":[" + OWNED + ",{\"status\":{\"_neq\":\"hidden\"}}]}";
        return PermissionDocs.document(PROJECT,
                PermissionDocs.entry(table("notes"), ROLE, PermissionDocs.select(readable, "\"*\""),
                        PermissionDocs.insert(NOT_LOCKED, "[\"id\",\"title\",\"status\",\"pinned\"]",
                                "{\"owner_id\":\"X-Excalibase-User-Id\"}"),
                        PermissionDocs.update(OWNED, NOT_LOCKED, "[\"id\",\"title\",\"status\"]", "{}"),
                        PermissionDocs.delete(OWNED)),
                PermissionDocs.entry(table("tags"), ROLE, PermissionDocs.everything()),
                PermissionDocs.entry(table("stamps"), ROLE, PermissionDocs.everything()),
                PermissionDocs.entry(table("counters"), ROLE, PermissionDocs.everything()),
                PermissionDocs.entry(table("drafts"), ROLE, PermissionDocs.selectAll(),
                        PermissionDocs.insert(hasAGate(), "[\"title\"]", "{\"owner_id\":\"X-Excalibase-User-Id\"}"),
                        PermissionDocs.update(OWNED, hasAGate(), "[\"title\"]", "{}")),
                PermissionDocs.entry(table("ledger"), ROLE, PermissionDocs.selectAll(),
                        PermissionDocs.insert("{\"_exists\":{\"_table\":\"" + table("counters") + "\",\"_where\":{}}}",
                                "[\"title\"]", "{}")),
                PermissionDocs.entry(table("profiles"), ROLE, PermissionDocs.everything()),
                PermissionDocs.entry(table("boards"), ROLE, PermissionDocs.selectAll(),
                        PermissionDocs.update(ownsABoard(), "{}", "[\"name\"]", "{}"),
                        PermissionDocs.delete(ownsABoard())),
                PermissionDocs.entry(table("sequenced"), ROLE, PermissionDocs.everything()),
                PermissionDocs.entry(table("feedback"), ROLE,
                        PermissionDocs.insert("{\"body\":{\"_neq\":\"spam\"}}", "[\"body\"]", "{}")),
                PermissionDocs.entry(table("vault"), ROLE,
                        PermissionDocs.select("{\"X-Excalibase-Aal\":{\"_eq\":\"aal2\"}}", "\"*\""),
                        PermissionDocs.delete("{\"X-Excalibase-Aal\":{\"_in\":[\"aal2\"]}}")));
    }

    private final ObjectMapper mapper = new ObjectMapper();

    private JdbcTemplate database() {
        return new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(),
                mysql.getPassword()));
    }

    private HttpAnswer as(String userId, String operation) throws Exception {
        return http.post("/" + PROJECT + "/graphql", mapper.writeValueAsString(Map.of("query", operation)), "Authorization", "Bearer " + jwt(userId), "Content-Type", "application/json");
    }

    @Test
    void select_readsOnlyTheRowsTheFilterKeeps() throws Exception {
        as(ALICE, "{ testNotes(where: { id: { in: [1, 2, 3] } }) { id title } }")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testNotes[*].title", contains("alice-1")));
    }

    @Test
    void insert_presetsTheOwner_andAnswersTheWrittenRow() throws Exception {
        as(ALICE, "mutation { createTestNotes(input: { title: \"preset\", pinned: true }) { title owner_id pinned } }")
                .expect(status().isOk())
                .expect(jsonPath("$.data.createTestNotes.title").value("preset"))
                .expect(jsonPath("$.data.createTestNotes.owner_id").value(ALICE));
    }

    @Test
    void insert_failingItsCheck_isRefused_andWritesNothing() throws Exception {
        as(ALICE, "mutation { createTestNotes(input: { title: \"locked-insert\", status: \"locked\" }) { id } }")
                .expect(status().isOk())
                .expect(jsonPath("$.errors[0].extensions.code").value("permission_check_failed"));
        assertThat(count("title = 'locked-insert'")).isZero();
    }

    @Test
    void insert_ofARowTheRoleCannotRead_answersNull_butIsWritten() throws Exception {
        as(ALICE, "mutation { createTestNotes(input: { title: \"hidden-insert\", status: \"hidden\" }) { id } }")
                .expect(status().isOk())
                .expect(jsonPath("$.data.createTestNotes").value(nullValue()));
        assertThat(count("title = 'hidden-insert'")).isOne();
    }

    @Test
    void createMany_withOneRowFailingItsCheck_writesNoRow() throws Exception {
        as(ALICE, "mutation { createManyTestNotes(inputs: [{ title: \"many-1\" },"
                + " { title: \"many-2\", status: \"locked\" }]) { id } }")
                .expect(jsonPath("$.errors[0].extensions.code").value("permission_check_failed"));
        assertThat(count("title IN ('many-1', 'many-2')")).isZero();
    }

    @Test
    void createMany_answersOnlyTheRowsTheRoleCanRead() throws Exception {
        as(ALICE, "mutation { createManyTestNotes(inputs: [{ title: \"read-1\" },"
                + " { title: \"read-2\", status: \"hidden\" }]) { title owner_id } }")
                .expect(status().isOk())
                .expect(jsonPath("$.data.createManyTestNotes[*].title", contains("read-1")))
                .expect(jsonPath("$.data.createManyTestNotes[0].owner_id").value(ALICE));
        assertThat(count("title IN ('read-1', 'read-2') AND owner_id = '" + ALICE + "'")).isEqualTo(2);
    }

    @Test
    void update_ofAnotherOwnersRow_changesNothing() throws Exception {
        as(ALICE, "mutation { updateTestNotes(where: { id: { eq: 2 } }, input: { title: \"hacked\" }) { id } }")
                .expect(status().isOk())
                .expect(jsonPath("$.data.updateTestNotes", hasSize(0)));
        assertThat(count("id = 2 AND title = 'bob-1'")).isOne();
    }

    @Test
    void update_failingItsCheck_rollsBack() throws Exception {
        insertNote(50, ALICE, "check-me", "open");

        as(ALICE, "mutation { updateTestNotes(where: { id: { eq: 50 } }, input: { status: \"locked\" }) { id } }")
                .expect(jsonPath("$.errors[0].extensions.code").value("permission_check_failed"));
        assertThat(count("id = 50 AND status = 'open'")).isOne();
    }

    @Test
    void update_answersOnlyTheRowsStillReadable() throws Exception {
        insertNote(60, ALICE, "to-hide", "open");
        insertNote(61, ALICE, "to-rename", "open");

        as(ALICE, "mutation { updateTestNotes(where: { id: { in: [60, 61] } }, input: { status: \"hidden\" }) { id } }")
                .expect(status().isOk())
                .expect(jsonPath("$.data.updateTestNotes", hasSize(0)));
        assertThat(count("id IN (60, 61) AND status = 'hidden'")).isEqualTo(2);
        as(ALICE, "mutation { updateTestNotes(where: { id: { eq: 61 } }, input: { status: \"open\" }) { id } }")
                .expect(jsonPath("$.data.updateTestNotes", hasSize(0)));
        assertThat(count("id = 61 AND status = 'hidden'")).isOne();
    }

    @Test
    void update_ofThePrimaryKey_isFollowedToTheRowItMovedTo() throws Exception {
        insertNote(65, ALICE, "keyed", "open");

        as(ALICE, "mutation { updateTestNotes(where: { id: { eq: 65 } }, input: { id: 66 }) { id title } }")
                .expect(jsonPath("$.data.updateTestNotes[0].id").value(66))
                .expect(jsonPath("$.data.updateTestNotes[0].title").value("keyed"));
        assertThat(count("id = 65")).isZero();
    }

    @Test
    void delete_removesOnlyPermittedRows_andAnswersThem() throws Exception {
        insertNote(70, ALICE, "mine", "open");
        insertNote(71, BOB, "theirs", "open");

        as(ALICE, "mutation { deleteTestNotes(where: { id: { in: [70, 71] } }) { id title } }")
                .expect(status().isOk())
                .expect(jsonPath("$.data.deleteTestNotes[*].title", contains("mine")));
        assertThat(count("id = 70")).isZero();
        assertThat(count("id = 71")).isOne();
    }

    @Test
    void onConflict_isRefusedLoudly_neverIgnored() throws Exception {
        as(ALICE, "mutation { createTestTags(input: { code: \"dup\", label: \"a\" },"
                + " onConflict: { constraint: \"PRIMARY\", update_columns: [label] }) { code } }")
                .expect(jsonPath("$.errors[0].message", containsString("onConflict")));
        assertThat(database().queryForObject("SELECT count(*) FROM tags WHERE code = 'dup'", Integer.class)).isZero();
    }

    @Test
    void everyMutationFieldRuns_inOrder() throws Exception {
        as(ALICE, "mutation { first: createTestTags(input: { code: \"t1\", label: \"one\" }) { code }"
                + " second: createTestTags(input: { code: \"t2\", label: \"two\" }) { code label } }")
                .expect(status().isOk())
                .expect(jsonPath("$.data.first.code").value("t1"))
                .expect(jsonPath("$.data.second.label").value("two"));
    }

    @Test
    void insert_whoseKeyIsNeitherSuppliedNorGenerated_failsLoudly() throws Exception {
        as(ALICE, "mutation { createTestStamps(input: { label: \"s\" }) { id } }")
                .expect(jsonPath("$.errors", hasSize(1)));
        assertThat(database().queryForObject("SELECT count(*) FROM stamps", Integer.class)).isZero();
    }

    @Test
    void insertOnly_answersTheCount_andStillChecksEveryRow() throws Exception {
        as(ALICE, "mutation { createManyTestFeedback(inputs: [{ body: \"nice\" }, { body: \"fine\" }]) { affected_rows } }")
                .expect(status().isOk())
                .expect(jsonPath("$.data.createManyTestFeedback.affected_rows").value(2));
        as(ALICE, "mutation { createTestFeedback(input: { body: \"spam\" }) { affected_rows } }")
                .expect(jsonPath("$.errors[0].extensions.code").value("permission_check_failed"));
        assertThat(database().queryForObject("SELECT count(*) FROM feedback WHERE body = 'spam'", Integer.class)).isZero();
    }

    @Test
    void permissionKeys_nameTheDatabaseAsTheSchema() {
        assertThat(mysql.getDatabaseName()).isEqualTo("test");
        assertThat(permissions()).contains("\"table\":\"test.notes\"");
    }

    @Test
    void aKeyMysqlStoresDifferently_neverSkipsTheCheck() throws Exception {
        as(ALICE, "mutation { createTestNotes(input: { id: 0, title: \"zero\", status: \"locked\" }) { id } }")
                .expect(jsonPath("$.errors", hasSize(1)));
        assertThat(count("title = 'zero'")).isZero();
    }

    @Test
    void eachFieldsCheck_runsOverItsOwnRows() throws Exception {
        as(ALICE, "mutation { a: createTestCounters(input: {}) { id }"
                + " b: createTestNotes(input: { id: 500, title: \"collide\", status: \"locked\" }) { id } }")
                .expect(jsonPath("$.errors[0].extensions.code").value("permission_check_failed"));
        assertThat(count("id = 500")).isZero();
    }

    @Test
    void aGeneratedValueThatIsNotTheKey_isRefused() throws Exception {
        as(ALICE, "mutation { createTestSequenced(input: { label: \"s\" }) { code } }")
                .expect(jsonPath("$.errors[0].message", containsString("AUTO_INCREMENT")));
        assertThat(database().queryForObject("SELECT count(*) FROM sequenced", Integer.class)).isZero();
    }

    private HttpAnswer rest(String userId, String method, String path, String body) throws Exception {
        return send(userId, method, path, "return=representation", body);
    }

    private HttpAnswer upsert(String userId, String path, String body) throws Exception {
        return send(userId, "POST", path, "return=representation, resolution=merge-duplicates", body);
    }

    private HttpAnswer send(String userId, String method, String path, String prefer, String body) throws Exception {
        return http.send(method, path, body, "Authorization", "Bearer " + jwt(userId),
                "Prefer", prefer, "Content-Type", "application/json");
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
    void restUpdate_failingItsCheck_rollsBack() throws Exception {
        insertNote(80, ALICE, "rest-check", "open");

        rest(ALICE, "PATCH", "/" + PROJECT + "/api/v1/notes?id=eq.80", "{\"status\":\"locked\"}")
                .expect(status().isForbidden());
        assertThat(count("id = 80 AND status = 'open'")).isOne();
    }

    @Test
    void restDelete_removesOnlyTheCallersRows() throws Exception {
        insertNote(90, ALICE, "rest-mine", "open");
        insertNote(91, BOB, "rest-theirs", "open");

        rest(ALICE, "DELETE", "/" + PROJECT + "/api/v1/notes?id=in.(90,91)", "")
                .expect(status().isOk())
                .expect(jsonPath("$.data[*].title", contains("rest-mine")));
        assertThat(count("id = 90")).isZero();
        assertThat(count("id = 91")).isOne();
    }

    @Test
    void restUpsert_neverOverwritesARowTheUpdateFilterHides() throws Exception {
        insertNote(85, BOB, "bobs", "open");
        insertNote(86, ALICE, "alices", "open");

        upsert(ALICE, "/" + PROJECT + "/api/v1/notes",
                "{\"id\":85,\"title\":\"hijacked\"}")
                .expect(status().is2xxSuccessful());
        upsert(ALICE, "/" + PROJECT + "/api/v1/notes",
                "{\"id\":86,\"title\":\"renamed\"}")
                .expect(status().is2xxSuccessful());
        assertThat(count("id = 85 AND title = 'bobs' AND owner_id = '" + BOB + "'")).isOne();
        assertThat(count("id = 86 AND title = 'renamed'")).isOne();
    }

    @Test
    void aFilterReadingTheTableItWrites_worksOnBothSurfaces() throws Exception {
        rest(ALICE, "PATCH", "/" + PROJECT + "/api/v1/boards?id=eq.1", "{\"name\":\"alice-was-here\"}")
                .expect(status().isOk())
                .expect(jsonPath("$.data", hasSize(0)));
        as(ALICE, "mutation { deleteTestBoards(where: { id: { eq: 1 } }) { id } }")
                .expect(jsonPath("$.data.deleteTestBoards", hasSize(0)));
        as(BOB, "mutation { updateTestBoards(where: { id: { eq: 1 } }, input: { name: \"bobs-board\" }) { name } }")
                .expect(jsonPath("$.data.updateTestBoards[0].name").value("bobs-board"));
        rest(BOB, "PATCH", "/" + PROJECT + "/api/v1/boards?id=eq.1", "{\"name\":\"rest-renamed\"}")
                .expect(status().isOk())
                .expect(jsonPath("$.data[0].name").value("rest-renamed"));
    }

    @Test
    void aCheckReadingTheTableItWrites_isRefused_neverJudgedAfterTheWrite() throws Exception {
        as(ALICE, "mutation { createTestDrafts(input: { title: \"gate\" }) { id } }")
                .expect(jsonPath("$.errors[0].message", containsString("cannot be enforced on MySQL")));
        rest(ALICE, "POST", "/" + PROJECT + "/api/v1/drafts", "{\"title\":\"gate\"}")
                .expect(status().isBadRequest());
        assertThat(database().queryForObject("SELECT count(*) FROM drafts", Integer.class)).isZero();

        database().update("INSERT INTO drafts (id, owner_id, title) VALUES (1, ?, 'draft')", ALICE);
        as(ALICE, "mutation { updateTestDrafts(where: { id: { eq: 1 } }, input: { title: \"gate\" }) { id } }")
                .expect(jsonPath("$.errors[0].message", containsString("cannot be enforced on MySQL")));
        assertThat(database().queryForObject("SELECT title FROM drafts WHERE id = 1", String.class)).isEqualTo("draft");
    }

    @Test
    void aCheckReadingATableAnEarlierFieldWrites_isRefused() throws Exception {
        as(ALICE, "mutation { createTestLedger(input: { title: \"alone\" }) { title } }")
                .expect(jsonPath("$.data.createTestLedger.title").value("alone"));
        as(ALICE, "mutation { a: createTestCounters(input: {}) { id } b: createTestLedger(input: { title: \"after\" }) { id } }")
                .expect(jsonPath("$.errors[0].message", containsString("cannot be enforced on MySQL")));
        assertThat(database().queryForObject("SELECT count(*) FROM ledger WHERE title = 'after'", Integer.class)).isZero();
    }

    @Test
    void restUpsert_collidingOnAUniqueKeyItDidNotSend_isAConflict_neverAnOverwrite() throws Exception {
        upsert(ALICE, "/" + PROJECT + "/api/v1/profiles",
                "{\"id\":999,\"org_id\":1,\"title\":\"pwned\"}")
                .expect(status().isConflict())
                .expect(jsonPath("$.code").value("unique_violation"));
        assertThat(database().queryForObject("SELECT title FROM profiles WHERE id = 1", String.class)).isEqualTo("bob");
        assertThat(database().queryForObject("SELECT count(*) FROM profiles WHERE id = 999", Integer.class)).isZero();
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

    // EXC-680 on MySQL: a session variable against a fixed value, through GraphQL and REST.
    @Test
    void aFixedAal2Check_decidesTheRequestOnBothSurfaces() throws Exception {
        for (Object[] expected : new Object[][] {{"aal2", 2}, {"aal1", 0}}) {
            http.post("/" + PROJECT + "/graphql", mapper.writeValueAsString(Map.of("query", "{ testVault { id } }")), "Authorization", "Bearer " + jwt(ALICE, (String) expected[0]), "Content-Type", "application/json")
                    .expect(status().isOk())
                    .expect(jsonPath("$.data.testVault", hasSize((Integer) expected[1])));
            http.get("/" + PROJECT + "/api/v1/vault", "Authorization", "Bearer " + jwt(ALICE, (String) expected[0]))
                    .expect(status().isOk())
                    .expect(jsonPath("$.data", hasSize((Integer) expected[1])));
        }
        http.post("/" + PROJECT + "/graphql", mapper.writeValueAsString(Map.of("query", "{ testVault { id } }")), "Authorization", "Bearer " + jwt(ALICE), "Content-Type", "application/json")
                .expect(jsonPath("$.errors[0].extensions.code").value("missing_session_variable"));
        rest(ALICE, "DELETE", "/" + PROJECT + "/api/v1/vault?id=eq.1", "")
                .expect(status().is4xxClientError());
        assertThat(database().queryForObject("SELECT count(*) FROM vault", Integer.class)).isEqualTo(2);
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
