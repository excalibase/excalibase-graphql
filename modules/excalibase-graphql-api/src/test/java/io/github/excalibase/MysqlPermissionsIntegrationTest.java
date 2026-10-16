package io.github.excalibase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.MySQLContainer;
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
import java.util.Base64;
import java.util.Date;
import java.util.Map;

import static io.github.excalibase.PermissionDocs.OWNED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A MySQL project with permissions on (EXC-618): the engine starts with a permission source and every
 * read and write is guarded as on Postgres. Writes on MySQL have no RETURNING, so the rows a mutation
 * writes are locked and keyed first; checks, presets and the select filter then apply to exactly those.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MysqlPermissionsIntegrationTest {

    private static final String ALICE = "11111111-1111-1111-1111-111111111111";
    private static final String BOB = "22222222-2222-2222-2222-222222222222";
    private static final String PROJECT = "proj-mysql";
    private static final String ROLE = "app_authenticated";
    private static final String NOT_LOCKED = "{\"status\":{\"_neq\":\"locked\"}}";

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4").withInitScript("init-mysql-permissions.sql");

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

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("app.database-type", () -> "mysql");
        registry.add("app.max-rows", () -> 30);
        registry.add("app.project-id", () -> PROJECT);
        registry.add("app.security.jwt-enabled", () -> "true");
        registry.add("app.security.auth.jwks-url",
                () -> "http://localhost:" + CONTROL_PLANE.getAddress().getPort() + "/.well-known/jwks.json");
        registry.add("app.security.rls.policy-url",
                () -> "http://localhost:" + CONTROL_PLANE.getAddress().getPort() + "/api");
        registry.add("app.security.rls.policy-pat", () -> "test-pat");
        registry.add("app.security.rls.policy-ttl-ms", () -> 0);
    }

    /**
     * Notes: a role reads its own rows that are not hidden, writes only rows that are not locked, and
     * every row it inserts is its own. Tags and stamps: everything.
     */
    private static String permissions() {
        String readable = "{\"_and\":[" + OWNED + ",{\"status\":{\"_neq\":\"hidden\"}}]}";
        return PermissionDocs.document(PROJECT,
                PermissionDocs.entry("test.notes", ROLE, PermissionDocs.select(readable, "\"*\""),
                        PermissionDocs.insert(NOT_LOCKED, "[\"title\",\"status\",\"pinned\"]",
                                "{\"owner_id\":\"X-Excalibase-User-Id\"}"),
                        PermissionDocs.update(OWNED, NOT_LOCKED, "[\"id\",\"title\",\"status\"]", "{}"),
                        PermissionDocs.delete(OWNED)),
                PermissionDocs.entry("test.tags", ROLE, PermissionDocs.everything()),
                PermissionDocs.entry("test.stamps", ROLE, PermissionDocs.everything()),
                PermissionDocs.entry("test.feedback", ROLE,
                        PermissionDocs.insert("{\"body\":{\"_neq\":\"spam\"}}", "[\"body\"]", "{}")));
    }

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper();

    private JdbcTemplate database() {
        return new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
    }

    private ResultActions as(String userId, String operation) throws Exception {
        return mockMvc.perform(post("/" + PROJECT + "/graphql")
                .header("Authorization", "Bearer " + jwt(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("query", operation))));
    }

    @Test
    void select_readsOnlyTheRowsTheFilterKeeps() throws Exception {
        as(ALICE, "{ testNotes(where: { id: { in: [1, 2, 3] } }) { id title } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testNotes[*].title", contains("alice-1")));
    }

    @Test
    void insert_presetsTheOwner_andAnswersTheWrittenRow() throws Exception {
        as(ALICE, "mutation { createTestNotes(input: { title: \"preset\", pinned: true }) { title owner_id pinned } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createTestNotes.title").value("preset"))
                .andExpect(jsonPath("$.data.createTestNotes.owner_id").value(ALICE));
    }

    @Test
    void insert_failingItsCheck_isRefused_andWritesNothing() throws Exception {
        as(ALICE, "mutation { createTestNotes(input: { title: \"locked-insert\", status: \"locked\" }) { id } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.code").value("permission_check_failed"));
        assertThat(count("title = 'locked-insert'")).isZero();
    }

    @Test
    void insert_ofARowTheRoleCannotRead_answersNull_butIsWritten() throws Exception {
        as(ALICE, "mutation { createTestNotes(input: { title: \"hidden-insert\", status: \"hidden\" }) { id } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createTestNotes").value(nullValue()));
        assertThat(count("title = 'hidden-insert'")).isOne();
    }

    @Test
    void createMany_withOneRowFailingItsCheck_writesNoRow() throws Exception {
        as(ALICE, "mutation { createManyTestNotes(inputs: [{ title: \"many-1\" },"
                + " { title: \"many-2\", status: \"locked\" }]) { id } }")
                .andExpect(jsonPath("$.errors[0].extensions.code").value("permission_check_failed"));
        assertThat(count("title IN ('many-1', 'many-2')")).isZero();
    }

    @Test
    void createMany_answersOnlyTheRowsTheRoleCanRead() throws Exception {
        as(ALICE, "mutation { createManyTestNotes(inputs: [{ title: \"read-1\" },"
                + " { title: \"read-2\", status: \"hidden\" }]) { title owner_id } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createManyTestNotes[*].title", contains("read-1")))
                .andExpect(jsonPath("$.data.createManyTestNotes[0].owner_id").value(ALICE));
        assertThat(count("title IN ('read-1', 'read-2') AND owner_id = '" + ALICE + "'")).isEqualTo(2);
    }

    @Test
    void update_ofAnotherOwnersRow_changesNothing() throws Exception {
        as(ALICE, "mutation { updateTestNotes(where: { id: { eq: 2 } }, input: { title: \"hacked\" }) { id } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updateTestNotes", hasSize(0)));
        assertThat(count("id = 2 AND title = 'bob-1'")).isOne();
    }

    @Test
    void update_failingItsCheck_rollsBack() throws Exception {
        insertNote(50, ALICE, "check-me", "open");

        as(ALICE, "mutation { updateTestNotes(where: { id: { eq: 50 } }, input: { status: \"locked\" }) { id } }")
                .andExpect(jsonPath("$.errors[0].extensions.code").value("permission_check_failed"));
        assertThat(count("id = 50 AND status = 'open'")).isOne();
    }

    @Test
    void update_answersOnlyTheRowsStillReadable() throws Exception {
        insertNote(60, ALICE, "to-hide", "open");
        insertNote(61, ALICE, "to-rename", "open");

        as(ALICE, "mutation { updateTestNotes(where: { id: { in: [60, 61] } }, input: { status: \"hidden\" }) { id } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updateTestNotes", hasSize(0)));
        assertThat(count("id IN (60, 61) AND status = 'hidden'")).isEqualTo(2);
        as(ALICE, "mutation { updateTestNotes(where: { id: { eq: 61 } }, input: { status: \"open\" }) { id } }")
                .andExpect(jsonPath("$.data.updateTestNotes", hasSize(0)));
        assertThat(count("id = 61 AND status = 'hidden'")).isOne();
    }

    @Test
    void update_ofThePrimaryKey_isRefused() throws Exception {
        insertNote(65, ALICE, "keyed", "open");

        as(ALICE, "mutation { updateTestNotes(where: { id: { eq: 65 } }, input: { id: 66 }) { id } }")
                .andExpect(jsonPath("$.errors[0].message", containsString("primary key")));
        assertThat(count("id = 65")).isOne();
    }

    @Test
    void delete_removesOnlyPermittedRows_andAnswersThem() throws Exception {
        insertNote(70, ALICE, "mine", "open");
        insertNote(71, BOB, "theirs", "open");

        as(ALICE, "mutation { deleteTestNotes(where: { id: { in: [70, 71] } }) { id title } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleteTestNotes[*].title", contains("mine")));
        assertThat(count("id = 70")).isZero();
        assertThat(count("id = 71")).isOne();
    }

    @Test
    void onConflict_isRefusedLoudly_neverIgnored() throws Exception {
        as(ALICE, "mutation { createTestTags(input: { code: \"dup\", label: \"a\" },"
                + " onConflict: { constraint: \"PRIMARY\", update_columns: [label] }) { code } }")
                .andExpect(jsonPath("$.errors[0].message", containsString("onConflict")));
        assertThat(database().queryForObject("SELECT count(*) FROM tags WHERE code = 'dup'", Integer.class)).isZero();
    }

    @Test
    void everyMutationFieldRuns_inOrder() throws Exception {
        as(ALICE, "mutation { first: createTestTags(input: { code: \"t1\", label: \"one\" }) { code }"
                + " second: createTestTags(input: { code: \"t2\", label: \"two\" }) { code label } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.first.code").value("t1"))
                .andExpect(jsonPath("$.data.second.label").value("two"));
    }

    @Test
    void insert_whoseKeyIsNeitherSuppliedNorGenerated_failsLoudly() throws Exception {
        as(ALICE, "mutation { createTestStamps(input: { label: \"s\" }) { id } }")
                .andExpect(jsonPath("$.errors", hasSize(1)));
        assertThat(database().queryForObject("SELECT count(*) FROM stamps", Integer.class)).isZero();
    }

    @Test
    void insertOnly_answersTheCount_andStillChecksEveryRow() throws Exception {
        as(ALICE, "mutation { createManyTestFeedback(inputs: [{ body: \"nice\" }, { body: \"fine\" }]) { affected_rows } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createManyTestFeedback.affected_rows").value(2));
        as(ALICE, "mutation { createTestFeedback(input: { body: \"spam\" }) { affected_rows } }")
                .andExpect(jsonPath("$.errors[0].extensions.code").value("permission_check_failed"));
        assertThat(database().queryForObject("SELECT count(*) FROM feedback WHERE body = 'spam'", Integer.class)).isZero();
    }

    private void insertNote(int id, String owner, String title, String noteStatus) {
        database().update("INSERT INTO notes (id, owner_id, title, status) VALUES (?, ?, ?, ?)", id, owner, title,
                noteStatus);
    }

    private int count(String condition) {
        return database().queryForObject("SELECT count(*) FROM notes WHERE " + condition, Integer.class);
    }

    private static String jwt(String userId) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("u@test.com").claim("userId", userId).claim("projectId", PROJECT)
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
