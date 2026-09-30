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

import static io.github.excalibase.PermissionDocs.OWNED;
import static io.github.excalibase.PermissionDocs.callable;
import static io.github.excalibase.PermissionDocs.entry;
import static io.github.excalibase.PermissionDocs.select;
import static io.github.excalibase.PermissionDocs.selectAll;
import static io.github.excalibase.PermissionDocs.tracked;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tracked functions end to end (docs/features/permissions.md §6) on a real Postgres over HTTP: only a
 * tracked function a role may call is reachable, through GraphQL and REST, and its rows and columns
 * are exactly what the role may select on the table it returns.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TrackedFunctionIntegrationTest {

    private static final String PROJECT = "proj-functions";
    private static final String USER = "user";
    private static final String NOTES = "public.notes";
    private static final String USER_COLUMNS = "[\"id\",\"owner_id\",\"body\",\"author_id\"]";

    private static final AtomicReference<String> PERMISSIONS = new AtomicReference<>();
    private static final AtomicLong VERSION = new AtomicLong();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-functions.sql");

    static ECPrivateKey privateKey;
    static HttpServer mockVault;
    static int mockVaultPort;

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
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                byte[] payload = jwksJson.getBytes(StandardCharsets.UTF_8);
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

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.database-type", () -> "postgres");
        registry.add("app.max-rows", () -> 30);
        registry.add("app.security.jwt-enabled", () -> "true");
        registry.add("app.project-id", () -> PROJECT);
        registry.add("app.security.auth.jwks-url",
                () -> "http://localhost:" + mockVaultPort + "/.well-known/jwks.json");
        registry.add("app.security.rls.policy-url", () -> "http://localhost:" + mockVaultPort + "/api");
        registry.add("app.security.rls.policy-pat", () -> "test-pat");
        registry.add("app.security.rls.policy-ttl-ms", () -> 0);
    }

    @Autowired
    private MockMvc mockMvc;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The user role reads its own notes without the secret column, and every author. */
    private static List<String> userTables() {
        return List.of(entry(NOTES, USER, select(OWNED, USER_COLUMNS)),
                entry("public.authors", USER, selectAll()));
    }

    /** The functions every test tracks unless it says otherwise; add_note needs an explicit permission. */
    private static List<String> trackedFunctions() {
        return List.of(
                tracked("public.search_notes", "QUERY", true, null),
                tracked("public.my_notes", "QUERY", true, "session"),
                tracked("public.add_note", "MUTATION", false, "session"),
                tracked("public.add_note_pair", "MUTATION", false, null),
                tracked("public.first_note", "QUERY", true, null),
                tracked("public.no_note", "QUERY", true, null));
    }

    private static void permit(List<String> tables, List<String> functions, List<String> functionPermissions) {
        PERMISSIONS.set(PermissionDocs.document(PROJECT, VERSION.incrementAndGet(), tables, functions,
                functionPermissions));
    }

    @BeforeEach
    void standardDocument() {
        permit(userTables(), trackedFunctions(),
                List.of(callable("public.add_note", USER), callable("public.add_note_pair", USER)));
    }

    private String jwt(String role, String scope, String userId) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(userId + "@test.com")
                .claim("userId", userId)
                .claim("projectId", PROJECT)
                .claim("role", role)
                .claim("scope", scope)
                .audience("excalibase:" + PROJECT)
                .issuer("excalibase")
                .issueTime(Date.from(Instant.parse("2024-01-01T00:00:00Z")))
                .expirationTime(Date.from(Instant.parse("2099-01-01T00:00:00Z")))
                .build();
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), claims);
        signed.sign(new ECDSASigner(privateKey));
        return signed.serialize();
    }

    private ResultActions graphql(String query) throws Exception {
        return graphqlAs(USER, "authenticated", query);
    }

    private ResultActions graphqlAs(String role, String scope, String query) throws Exception {
        return mockMvc.perform(post("/" + PROJECT + "/graphql")
                .header("Authorization", "Bearer " + jwt(role, scope, "u-1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("query", query))));
    }

    /** {@code params} are name/value pairs, passed as query parameters. */
    private ResultActions restGet(String function, String... params) throws Exception {
        var request = get("/" + PROJECT + "/api/v1/rpc/" + function)
                .header("Authorization", "Bearer " + jwt(USER, "authenticated", "u-1"));
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mockMvc.perform(request);
    }

    private ResultActions restPost(String function, String body, String... params) throws Exception {
        var request = post("/" + PROJECT + "/api/v1/rpc/" + function);
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mockMvc.perform(request
                .header("Authorization", "Bearer " + jwt(USER, "authenticated", "u-1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    // ---- GraphQL queries ----

    @Test
    void aQueryFunction_returnsOnlyTheRowsAndColumnsTheRoleMaySelect() throws Exception {
        graphql("{ publicSearchNotes(p_query: \"alpha%\") { id owner_id body } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.publicSearchNotes", hasSize(1)))
                .andExpect(jsonPath("$.data.publicSearchNotes[0].id").value(1));
        graphql("{ publicSearchNotes(p_query: \"alpha%\") { id secret } }")
                .andExpect(jsonPath("$.errors[0].message", containsString("Unknown field")));
    }

    @Test
    void aQueryFunction_takesWhereLimitAndNestedRelationships() throws Exception {
        graphql("{ publicSearchNotes(p_query: \"%a%\", where: { id: { gt: 1 } }, limit: 5) "
                + "{ id publicAuthorId { name } } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.publicSearchNotes", hasSize(1)))
                .andExpect(jsonPath("$.data.publicSearchNotes[0].id").value(3))
                .andExpect(jsonPath("$.data.publicSearchNotes[0].publicAuthorId.name").value("ben"));
    }

    @Test
    void theSessionArgument_receivesTheSessionVariables_andIsNotAClientArgument() throws Exception {
        graphql("{ publicMyNotes(where: { id: { lt: 100 } }) { id } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.publicMyNotes[*].id", containsInAnyOrder(1, 3)));
        graphql("{ publicMyNotes(session: \"{}\") { id } }")
                .andExpect(jsonPath("$.errors[0].message", containsString("Unknown argument session")));
    }

    @Test
    void anUntrackedFunction_isAnUnknownField_andNotFoundOverRest() throws Exception {
        graphql("{ publicUntrackedNotes { id } }")
                .andExpect(jsonPath("$.errors[0].message", containsString("Unknown field")));
        graphqlAs("service", "service", "{ publicUntrackedNotes { id } }")
                .andExpect(jsonPath("$.errors[0].message", containsString("Unknown field")));
        restGet("untracked_notes").andExpect(status().isNotFound());
    }

    @Test
    void inference_grantsAStableFunction_andTurnedOff_onlyAnExplicitPermissionDoes() throws Exception {
        List<String> noInference = List.of(tracked("public.search_notes", "QUERY", false, null));
        permit(userTables(), noInference, List.of());
        graphql("{ publicSearchNotes(p_query: \"%a%\") { id } }")
                .andExpect(jsonPath("$.errors[0].message", containsString("Unknown field")));

        permit(userTables(), noInference, List.of(callable("public.search_notes", USER)));
        graphql("{ publicSearchNotes(p_query: \"%a%\") { id } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.publicSearchNotes", hasSize(2)));
    }

    @Test
    void aRoleThatCannotSelectTheReturnTable_hasNoFunction() throws Exception {
        permit(List.of(entry("public.authors", USER, selectAll())), trackedFunctions(),
                List.of(callable("public.add_note", USER)));

        graphql("{ __schema { queryType { fields { name } } mutationType { fields { name } } } }")
                .andExpect(content().string(not(containsString("publicSearchNotes"))))
                .andExpect(content().string(not(containsString("publicAddNote"))));
        restGet("search_notes", "p_query", "%").andExpect(status().isNotFound());
    }

    @Test
    void aSingleRowFunction_answersAnObject_orNull() throws Exception {
        graphql("{ publicFirstNote { id body } publicNoNote { id } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.publicFirstNote.id").value(1))
                .andExpect(jsonPath("$.data.publicNoNote").doesNotExist());
    }

    @Test
    void aSecurityDefinerFunction_isCalled_andDescribedAsRunningAsItsOwner() throws Exception {
        graphql("{ __type(name: \"Query\") { fields { name description } } }")
                .andExpect(content().string(containsString("privileges of its owner (SECURITY DEFINER)")));
        graphql("{ publicFirstNote { id owner_id } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.publicFirstNote.owner_id").value("u-1"));
    }

    // ---- GraphQL mutations ----

    @Test
    void aMutationFunction_writes_thenReturnsOnlyTheRowsTheRoleMaySelect() throws Exception {
        graphql("mutation { publicAddNotePair(p_id: 200) { id owner_id } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.publicAddNotePair", hasSize(1)))
                .andExpect(jsonPath("$.data.publicAddNotePair[0].id").value(200));
        graphqlAs("service", "service", "{ publicNotes(where: { id: { in: [200, 201] } }) { id owner_id } }")
                .andExpect(jsonPath("$.data.publicNotes", hasSize(2)));
    }

    @Test
    void aMutationFunction_receivesTheSession_andIsNoQueryField() throws Exception {
        graphql("mutation { publicAddNote(p_id: 210, p_body: \"hello\") { id owner_id body } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.publicAddNote[0].owner_id").value("u-1"));
        graphql("{ publicAddNote(p_id: 211, p_body: \"nope\") { id } }")
                .andExpect(jsonPath("$.errors[0].message", containsString("Unknown field")));
    }

    @Test
    void aMutationFunction_needsAnExplicitPermission() throws Exception {
        permit(userTables(), trackedFunctions(), List.of());

        graphql("mutation { publicAddNote(p_id: 220, p_body: \"x\") { id } }")
                .andExpect(jsonPath("$.data.publicAddNote").doesNotExist());
        restPost("add_note", "{\"p_id\":220,\"p_body\":\"x\"}").andExpect(status().isNotFound());
    }

    // ---- REST ----

    @Test
    void restGet_callsAQueryFunction_withArgumentsAndRowParameters() throws Exception {
        restGet("search_notes", "p_query", "alpha%", "select", "id,body")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].owner_id").doesNotExist());
        restGet("search_notes", "p_query", "%a%", "id", "eq.3")
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(3))
                .andExpect(jsonPath("$.data[0].secret").doesNotExist());
        restGet("first_note").andExpect(jsonPath("$.data.id").value(1));
    }

    @Test
    void restPost_callsAQueryFunction_withAJsonBody() throws Exception {
        restPost("search_notes", "{\"p_query\":\"%a%\"}", "order", "id.desc")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id", containsInAnyOrder(3, 1)))
                .andExpect(jsonPath("$.data[0].id").value(3));
        restPost("search_notes", "{}").andExpect(status().isBadRequest());
    }

    @Test
    void restMutationFunction_isPostOnly_andReturnsOnlyVisibleRows() throws Exception {
        restGet("add_note_pair", "p_id", "300").andExpect(status().isMethodNotAllowed());
        restPost("add_note_pair", "{\"p_id\":300}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(300));
    }

    // ---- service ----

    @Test
    void service_callsEveryTrackedFunction_withEveryColumn() throws Exception {
        graphqlAs("service", "service", "{ publicSearchNotes(p_query: \"alpha%\") { id secret } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.publicSearchNotes", hasSize(2)));
    }
}
