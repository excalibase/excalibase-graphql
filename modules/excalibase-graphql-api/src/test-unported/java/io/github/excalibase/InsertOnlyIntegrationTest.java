package io.github.excalibase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Map;

import static io.github.excalibase.PermissionDocs.entry;
import static io.github.excalibase.PermissionDocs.insert;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Insert without select (docs/features/permissions.md §5), over HTTP on real Postgres: anon may post to a
 * contact form it can never read. The insert, its preset and its check apply; what comes back is the
 * affected-row count, and the table has no read path on any surface.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class InsertOnlyIntegrationTest {

    private static final String PROJECT = "proj-insert-only";
    private static final String ANON = "anon";
    private static final String CONTACT_FORM = PermissionDocs.document(PROJECT, entry("public.messages", ANON,
            insert("{\"email\":{\"_like\":\"%@%\"}}", "[\"email\",\"body\",\"source\"]", "{\"source\":\"contact-form\"}")));

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
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

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.database-type", () -> "postgres");
        registry.add("app.security.jwt-enabled", () -> "true");
        registry.add("app.project-id", () -> PROJECT);
        registry.add("app.security.auth.jwks-url",
                () -> "http://localhost:" + mockVaultPort + "/.well-known/jwks.json");
        registry.add("app.security.rls.policy-url", () -> "http://localhost:" + mockVaultPort + "/api");
        registry.add("app.security.rls.policy-pat", () -> "test-pat");
        registry.add("app.security.rls.policy-ttl-ms", () -> 0);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private ResultActions graphql(String query) throws Exception {
        return mockMvc.perform(post("/" + PROJECT + "/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("query", query))));
    }

    private ResultActions rest(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Accept-Profile", "public").header("Content-Profile", "public"));
    }

    private ResultActions restPost(String body, String prefer) throws Exception {
        MockHttpServletRequestBuilder request = post("/" + PROJECT + "/api/v1/messages")
                .contentType(MediaType.APPLICATION_JSON).content(body);
        return rest(prefer == null ? request : request.header("Prefer", prefer));
    }

    private int rowsWithBody(String body) {
        return jdbc.queryForObject("SELECT count(*) FROM messages WHERE body = ?", Integer.class, body);
    }

    // ---- GraphQL ----

    @Test
    void create_writesTheRowWithItsPreset_andAnswersOnlyTheCount() throws Exception {
        graphql("mutation { createPublicMessages(input: { email: \"a@test.com\", body: \"gql-one\" }) "
                + "{ __typename affected_rows } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.createPublicMessages.affected_rows").value(1))
                .andExpect(jsonPath("$.data.createPublicMessages.__typename").doesNotExist());

        assertOneRow("gql-one", "contact-form");
    }

    @Test
    void createMany_countsEveryWrittenRow() throws Exception {
        graphql("mutation { createManyPublicMessages(inputs: [{ email: \"a@test.com\", body: \"gql-many\" }, "
                + "{ email: \"b@test.com\", body: \"gql-many\" }]) { affected_rows } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.createManyPublicMessages.affected_rows").value(2));

        assertThat(rowsWithBody("gql-many")).isEqualTo(2);
    }

    @Test
    void aFailedCheck_rollsBackTheWholeWrite() throws Exception {
        graphql("mutation { createPublicMessages(input: { email: \"no-at-sign\", body: \"gql-bad\" }) { affected_rows } }")
                .andExpect(jsonPath("$.errors[0].extensions.code").value("permission_check_failed"));
        graphql("mutation { createManyPublicMessages(inputs: [{ email: \"ok@test.com\", body: \"gql-bad-many\" }, "
                + "{ email: \"nope\", body: \"gql-bad-many\" }]) { affected_rows } }")
                .andExpect(jsonPath("$.errors[0].extensions.code").value("permission_check_failed"));

        assertThat(rowsWithBody("gql-bad")).isZero();
        assertThat(rowsWithBody("gql-bad-many")).isZero();
    }

    @Test
    void thePresetColumn_cannotBeSetByTheClient() throws Exception {
        graphql("mutation { createPublicMessages(input: { email: \"a@test.com\", body: \"gql-forged\", "
                + "source: \"forged\" }) { affected_rows } }")
                .andExpect(jsonPath("$.errors[0].message", containsString("source")));

        assertThat(rowsWithBody("gql-forged")).isZero();
    }

    @Test
    void noReadPath_exists() throws Exception {
        graphql("{ publicMessages { id } }")
                .andExpect(jsonPath("$.errors[0].message", containsString("Unknown field")))
                .andExpect(jsonPath("$.data").doesNotExist());
        graphql("mutation { createPublicMessages(input: { email: \"a@test.com\", body: \"gql-peek\" }) { id } }")
                .andExpect(jsonPath("$.errors[0].message", containsString("Unknown field(s): id")));
        graphql("mutation { deletePublicMessages(where: { id: { eq: 1 } }) { affected_rows } }")
                .andExpect(jsonPath("$.errors[0].message", containsString("Unknown field")));

        assertThat(rowsWithBody("gql-peek")).isZero();
        assertThat(rowsWithBody("already here")).isEqualTo(1);
    }

    @Test
    void introspection_showsOnlyTheInsertFields_andTheirCountType() throws Exception {
        graphql("{ __schema { queryType { fields { name } } } }")
                .andExpect(content().string(not(containsString("Messages"))));
        graphql("{ __schema { mutationType { fields { name type { name } } } } }")
                .andExpect(jsonPath("$.data.__schema.mutationType.fields[?(@.name == 'createPublicMessages')].type.name")
                        .value("PublicMessages_InsertResult"))
                .andExpect(jsonPath("$.data.__schema.mutationType.fields[?(@.name == 'createManyPublicMessages')].type.name")
                        .value("PublicMessages_InsertResult"))
                .andExpect(content().string(not(containsString("updatePublicMessages"))))
                .andExpect(content().string(not(containsString("deletePublicMessages"))));
        graphql("{ __type(name: \"PublicMessages_InsertResult\") { fields { name type { kind ofType { name } } } } }")
                .andExpect(jsonPath("$.data.__type.fields.length()").value(1))
                .andExpect(jsonPath("$.data.__type.fields[0].name").value("affected_rows"))
                .andExpect(jsonPath("$.data.__type.fields[0].type.kind").value("NON_NULL"))
                .andExpect(jsonPath("$.data.__type.fields[0].type.ofType.name").value("Int"));
        graphql("{ __type(name: \"PublicMessages\") { name } }")
                .andExpect(jsonPath("$.data.__type").doesNotExist());
    }

    // ---- REST ----

    @Test
    void rest_post_isCreated_withNoBody() throws Exception {
        restPost("{\"email\":\"r@test.com\",\"body\":\"rest-min\"}", null)
                .andExpect(status().isCreated())
                .andExpect(content().string(""));

        assertOneRow("rest-min", "contact-form");
    }

    @Test
    void rest_post_returnRepresentation_representsNoRow() throws Exception {
        restPost("{\"email\":\"r@test.com\",\"body\":\"rest-rep\"}", "return=representation")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.length()").value(0));
        restPost("[{\"email\":\"r@test.com\",\"body\":\"rest-bulk\"},{\"email\":\"s@test.com\",\"body\":\"rest-bulk\"}]",
                "return=representation")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.length()").value(0));

        assertOneRow("rest-rep", "contact-form");
        assertThat(rowsWithBody("rest-bulk")).isEqualTo(2);
    }

    @Test
    void rest_post_failedCheck_isForbidden_andWritesNothing() throws Exception {
        restPost("{\"email\":\"nope\",\"body\":\"rest-bad\"}", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("permission_check_failed"));

        assertThat(rowsWithBody("rest-bad")).isZero();
    }

    @Test
    void rest_post_upsert_isForbidden() throws Exception {
        restPost("{\"id\":1,\"email\":\"r@test.com\",\"body\":\"rest-upsert\"}", "resolution=merge-duplicates")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("permission_denied"));
    }

    @Test
    void rest_readsAndOtherWrites_areNotFound() throws Exception {
        rest(get("/" + PROJECT + "/api/v1/messages")).andExpect(status().isNotFound());
        rest(patch("/" + PROJECT + "/api/v1/messages?id=eq.1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"changed\"}")).andExpect(status().isNotFound());
        rest(delete("/" + PROJECT + "/api/v1/messages?id=eq.1")).andExpect(status().isNotFound());
        rest(get("/" + PROJECT + "/api/v1"))
                .andExpect(jsonPath("$.paths['/messages'].get").doesNotExist())
                .andExpect(jsonPath("$.paths['/messages'].post").exists());

        assertThat(rowsWithBody("already here")).isEqualTo(1);
    }

    private void assertOneRow(String body, String source) {
        assertThat(
                jdbc.queryForObject("SELECT count(*) FROM messages WHERE body = ? AND source = ?", Integer.class,
                        body, source)).isEqualTo(1);
    }
}
