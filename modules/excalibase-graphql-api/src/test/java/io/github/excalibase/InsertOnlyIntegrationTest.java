package io.github.excalibase;

import io.github.excalibase.support.DatabaseAccess;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
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
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;

import static io.github.excalibase.support.Expect.content;
import static io.github.excalibase.support.Expect.jsonPath;
import static io.github.excalibase.support.Expect.status;
import static io.github.excalibase.PermissionDocs.entry;
import static io.github.excalibase.PermissionDocs.insert;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Insert without select (docs/features/permissions.md §5), over HTTP on real Postgres: anon may post to a
 * contact form it can never read. The insert, its preset and its check apply; what comes back is the
 * affected-row count, and the table has no read path on any surface.
 */
class InsertOnlyIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "proj-insert-only";
    private static final String ANON = "anon";
    private static final String CONTACT_FORM = PermissionDocs.document(PROJECT, entry("public.messages", ANON,
            insert("{\"email\":{\"_like\":\"%@%\"}}", "[\"email\",\"body\",\"source\"]", "{\"source\":\"contact-form\"}")));

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-insert-only.sql");

    static HttpServer mockVault;
    static int mockVaultPort;

    static {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair keyPair = gen.generateKeyPair();
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
            PermissionDocs.serve(mockVault, PROJECT, () -> CONTACT_FORM);
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
    void openJdbc() {
        jdbc = DatabaseAccess.template(postgres);
    }

    private HttpAnswer graphql(String query) throws Exception {
        return http.post("/" + PROJECT + "/graphql", MAPPER.writeValueAsString(Map.of("query", query)), "Content-Type", "application/json");
    }

    private HttpAnswer rest(String method, String path, String body, String... headers) {
        String[] profiles = {"Accept-Profile", "public", "Content-Profile", "public"};
        String[] all = Stream.concat(Arrays.stream(profiles), Arrays.stream(headers)).toArray(String[]::new);
        return http.send(method, path, body, all);
    }

    private HttpAnswer restPost(String body, String prefer) {
        String path = "/" + PROJECT + "/api/v1/messages";
        return prefer == null ? rest("POST", path, body, "Content-Type", "application/json")
                : rest("POST", path, body, "Content-Type", "application/json", "Prefer", prefer);
    }

    private int rowsWithBody(String body) {
        return jdbc.queryForObject("SELECT count(*) FROM messages WHERE body = ?", Integer.class, body);
    }

    // ---- GraphQL ----

    @Test
    void create_writesTheRowWithItsPreset_andAnswersOnlyTheCount() throws Exception {
        graphql("mutation { createPublicMessages(input: { email: \"a@test.com\", body: \"gql-one\" }) "
                + "{ __typename affected_rows } }")
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.createPublicMessages.affected_rows").value(1))
                .expect(jsonPath("$.data.createPublicMessages.__typename").doesNotExist());

        assertOneRow("gql-one", "contact-form");
    }

    @Test
    void createMany_countsEveryWrittenRow() throws Exception {
        graphql("mutation { createManyPublicMessages(inputs: [{ email: \"a@test.com\", body: \"gql-many\" }, "
                + "{ email: \"b@test.com\", body: \"gql-many\" }]) { affected_rows } }")
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.createManyPublicMessages.affected_rows").value(2));

        assertThat(rowsWithBody("gql-many")).isEqualTo(2);
    }

    @Test
    void aFailedCheck_rollsBackTheWholeWrite() throws Exception {
        graphql("mutation { createPublicMessages(input: { email: \"no-at-sign\", body: \"gql-bad\" }) { affected_rows } }")
                .expect(jsonPath("$.errors[0].extensions.code").value("permission_check_failed"));
        graphql("mutation { createManyPublicMessages(inputs: [{ email: \"ok@test.com\", body: \"gql-bad-many\" }, "
                + "{ email: \"nope\", body: \"gql-bad-many\" }]) { affected_rows } }")
                .expect(jsonPath("$.errors[0].extensions.code").value("permission_check_failed"));

        assertThat(rowsWithBody("gql-bad")).isZero();
        assertThat(rowsWithBody("gql-bad-many")).isZero();
    }

    @Test
    void thePresetColumn_cannotBeSetByTheClient() throws Exception {
        graphql("mutation { createPublicMessages(input: { email: \"a@test.com\", body: \"gql-forged\", "
                + "source: \"forged\" }) { affected_rows } }")
                .expect(jsonPath("$.errors[0].message", containsString("source")));

        assertThat(rowsWithBody("gql-forged")).isZero();
    }

    @Test
    void noReadPath_exists() throws Exception {
        graphql("{ publicMessages { id } }")
                .expect(jsonPath("$.errors[0].message", containsString("Unknown field")))
                .expect(jsonPath("$.data").doesNotExist());
        graphql("mutation { createPublicMessages(input: { email: \"a@test.com\", body: \"gql-peek\" }) { id } }")
                .expect(jsonPath("$.errors[0].message", containsString("Unknown field(s): id")));
        graphql("mutation { deletePublicMessages(where: { id: { eq: 1 } }) { affected_rows } }")
                .expect(jsonPath("$.errors[0].message", containsString("Unknown field")));

        assertThat(rowsWithBody("gql-peek")).isZero();
        assertThat(rowsWithBody("already here")).isEqualTo(1);
    }

    @Test
    void introspection_showsOnlyTheInsertFields_andTheirCountType() throws Exception {
        graphql("{ __schema { queryType { fields { name } } } }")
                .expect(content().string(not(containsString("Messages"))));
        graphql("{ __schema { mutationType { fields { name type { name } } } } }")
                .expect(jsonPath("$.data.__schema.mutationType.fields[?(@.name == 'createPublicMessages')].type.name")
                        .value("PublicMessages_InsertResult"))
                .expect(jsonPath("$.data.__schema.mutationType.fields[?(@.name == 'createManyPublicMessages')].type.name")
                        .value("PublicMessages_InsertResult"))
                .expect(content().string(not(containsString("updatePublicMessages"))))
                .expect(content().string(not(containsString("deletePublicMessages"))));
        graphql("{ __type(name: \"PublicMessages_InsertResult\") { fields { name type { kind ofType { name } } } } }")
                .expect(jsonPath("$.data.__type.fields.length()").value(1))
                .expect(jsonPath("$.data.__type.fields[0].name").value("affected_rows"))
                .expect(jsonPath("$.data.__type.fields[0].type.kind").value("NON_NULL"))
                .expect(jsonPath("$.data.__type.fields[0].type.ofType.name").value("Int"));
        graphql("{ __type(name: \"PublicMessages\") { name } }")
                .expect(jsonPath("$.data.__type").doesNotExist());
    }

    // ---- REST ----

    @Test
    void rest_post_isCreated_withNoBody() throws Exception {
        restPost("{\"email\":\"r@test.com\",\"body\":\"rest-min\"}", null)
                .expect(status().isCreated())
                .expect(content().string(""));

        assertOneRow("rest-min", "contact-form");
    }

    @Test
    void rest_post_returnRepresentation_representsNoRow() throws Exception {
        restPost("{\"email\":\"r@test.com\",\"body\":\"rest-rep\"}", "return=representation")
                .expect(status().isCreated())
                .expect(jsonPath("$.data.length()").value(0));
        restPost("[{\"email\":\"r@test.com\",\"body\":\"rest-bulk\"},{\"email\":\"s@test.com\",\"body\":\"rest-bulk\"}]",
                "return=representation")
                .expect(status().isCreated())
                .expect(jsonPath("$.data.length()").value(0));

        assertOneRow("rest-rep", "contact-form");
        assertThat(rowsWithBody("rest-bulk")).isEqualTo(2);
    }

    @Test
    void rest_post_failedCheck_isForbidden_andWritesNothing() throws Exception {
        restPost("{\"email\":\"nope\",\"body\":\"rest-bad\"}", null)
                .expect(status().isForbidden())
                .expect(jsonPath("$.code").value("permission_check_failed"));

        assertThat(rowsWithBody("rest-bad")).isZero();
    }

    @Test
    void rest_post_upsert_isForbidden() throws Exception {
        restPost("{\"id\":1,\"email\":\"r@test.com\",\"body\":\"rest-upsert\"}", "resolution=merge-duplicates")
                .expect(status().isForbidden())
                .expect(jsonPath("$.code").value("permission_denied"));
    }

    @Test
    void rest_post_bulkUpsert_isForbidden() {
        restPost("[{\"id\":1,\"email\":\"r@test.com\",\"body\":\"rest-bulk-upsert\"}]", "resolution=merge-duplicates")
                .expect(status().isForbidden())
                .expect(jsonPath("$.code").value("permission_denied"));
        assertThat(rowsWithBody("rest-bulk-upsert")).isZero();
    }

    @Test
    void rest_readsAndOtherWrites_areNotFound() throws Exception {
        rest("GET", "/" + PROJECT + "/api/v1/messages", null).expect(status().isNotFound());
        rest("PATCH", "/" + PROJECT + "/api/v1/messages?id=eq.1", "{\"body\":\"changed\"}",
                "Content-Type", "application/json").expect(status().isNotFound());
        rest("DELETE", "/" + PROJECT + "/api/v1/messages?id=eq.1", null).expect(status().isNotFound());
        rest("GET", "/" + PROJECT + "/api/v1", null)
                .expect(jsonPath("$.paths['/messages'].get").doesNotExist())
                .expect(jsonPath("$.paths['/messages'].post").exists());

        assertThat(rowsWithBody("already here")).isEqualTo(1);
    }

    private void assertOneRow(String body, String source) {
        assertThat(
                jdbc.queryForObject("SELECT count(*) FROM messages WHERE body = ? AND source = ?", Integer.class,
                        body, source)).isEqualTo(1);
    }
}
