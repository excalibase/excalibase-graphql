package io.github.excalibase;

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
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.InetSocketAddress;
import java.net.URLEncoder;
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

import static io.github.excalibase.support.Expect.content;
import static io.github.excalibase.support.Expect.jsonPath;
import static io.github.excalibase.support.Expect.status;
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

/**
 * Tracked functions end to end (docs/features/permissions.md §6) on a real Postgres over HTTP: only a
 * tracked function a role may call is reachable, through GraphQL and REST, and its rows and columns
 * are exactly what the role may select on the table it returns.
 */
class TrackedFunctionIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "proj-functions";
    private static final String USER = "user";
    private static final String NOTES = "public.notes";
    private static final String USER_COLUMNS = "[\"id\",\"owner_id\",\"body\",\"author_id\"]";

    private static final AtomicReference<String> PERMISSIONS = new AtomicReference<>();
    private static final AtomicLong VERSION = new AtomicLong();

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
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

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = EngineProperties.datasource(postgres);
        properties.put("app.database-type", "postgres");
        properties.put("app.max-rows", "30");
        properties.put("app.security.jwt-enabled", "true");
        properties.put("app.project-id", PROJECT);
        properties.put("app.security.auth.jwks-url", "http://localhost:" + mockVaultPort + "/.well-known/jwks.json");
        properties.put("app.security.rls.policy-url", "http://localhost:" + mockVaultPort + "/api");
        properties.put("app.security.rls.policy-pat", "test-pat");
        properties.put("app.security.rls.policy-ttl-ms", "0");
        return properties;
    }


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

    private HttpAnswer graphql(String query) throws Exception {
        return graphqlAs(USER, "authenticated", query);
    }

    private HttpAnswer graphqlAs(String role, String scope, String query) throws Exception {
        return http.post("/" + PROJECT + "/graphql", MAPPER.writeValueAsString(Map.of("query", query)), "Content-Type", "application/json", "Authorization", "Bearer " + jwt(role, scope, "u-1"));
    }

    /** {@code params} are name/value pairs, passed as query parameters. */
    private HttpAnswer restGet(String function, String... params) throws Exception {
        return http.get("/" + PROJECT + "/api/v1/rpc/" + function + queryString(params),
                "Authorization", "Bearer " + jwt(USER, "authenticated", "u-1"));
    }

    private HttpAnswer restPost(String function, String body, String... params) throws Exception {
        return http.post("/" + PROJECT + "/api/v1/rpc/" + function + queryString(params), body,
                "Authorization", "Bearer " + jwt(USER, "authenticated", "u-1"), "Content-Type", "application/json");
    }

    private static String queryString(String... pairs) {
        StringBuilder query = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            query.append(i == 0 ? '?' : '&').append(URLEncoder.encode(pairs[i], StandardCharsets.UTF_8))
                    .append('=').append(URLEncoder.encode(pairs[i + 1], StandardCharsets.UTF_8));
        }
        return query.toString();
    }

    // ---- GraphQL queries ----

    @Test
    void aQueryFunction_returnsOnlyTheRowsAndColumnsTheRoleMaySelect() throws Exception {
        graphql("{ publicSearchNotes(p_query: \"alpha%\") { id owner_id body } }")
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.publicSearchNotes", hasSize(1)))
                .expect(jsonPath("$.data.publicSearchNotes[0].id").value(1));
        graphql("{ publicSearchNotes(p_query: \"alpha%\") { id secret } }")
                .expect(jsonPath("$.errors[0].message", containsString("Unknown field")));
    }

    @Test
    void aQueryFunction_takesWhereLimitAndNestedRelationships() throws Exception {
        graphql("{ publicSearchNotes(p_query: \"%a%\", where: { id: { gt: 1 } }, limit: 5) "
                + "{ id publicAuthorId { name } } }")
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.publicSearchNotes", hasSize(1)))
                .expect(jsonPath("$.data.publicSearchNotes[0].id").value(3))
                .expect(jsonPath("$.data.publicSearchNotes[0].publicAuthorId.name").value("ben"));
    }

    @Test
    void theSessionArgument_receivesTheSessionVariables_andIsNotAClientArgument() throws Exception {
        graphql("{ publicMyNotes(where: { id: { lt: 100 } }) { id } }")
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.publicMyNotes[*].id", containsInAnyOrder(1, 3)));
        graphql("{ publicMyNotes(session: \"{}\") { id } }")
                .expect(jsonPath("$.errors[0].message", containsString("Unknown argument session")));
    }

    @Test
    void anUntrackedFunction_isAnUnknownField_andNotFoundOverRest() throws Exception {
        graphql("{ publicUntrackedNotes { id } }")
                .expect(jsonPath("$.errors[0].message", containsString("Unknown field")));
        graphqlAs("service", "service", "{ publicUntrackedNotes { id } }")
                .expect(jsonPath("$.errors[0].message", containsString("Unknown field")));
        restGet("untracked_notes").expect(status().isNotFound());
    }

    @Test
    void inference_grantsAStableFunction_andTurnedOff_onlyAnExplicitPermissionDoes() throws Exception {
        List<String> noInference = List.of(tracked("public.search_notes", "QUERY", false, null));
        permit(userTables(), noInference, List.of());
        graphql("{ publicSearchNotes(p_query: \"%a%\") { id } }")
                .expect(jsonPath("$.errors[0].message", containsString("Unknown field")));

        permit(userTables(), noInference, List.of(callable("public.search_notes", USER)));
        graphql("{ publicSearchNotes(p_query: \"%a%\") { id } }")
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.publicSearchNotes", hasSize(2)));
    }

    @Test
    void aRoleThatCannotSelectTheReturnTable_hasNoFunction() throws Exception {
        permit(List.of(entry("public.authors", USER, selectAll())), trackedFunctions(),
                List.of(callable("public.add_note", USER)));

        graphql("{ __schema { queryType { fields { name } } mutationType { fields { name } } } }")
                .expect(content().string(not(containsString("publicSearchNotes"))))
                .expect(content().string(not(containsString("publicAddNote"))));
        restGet("search_notes", "p_query", "%").expect(status().isNotFound());
    }

    @Test
    void rest_aWritingFunctionThatLosesItsConnectionAtCommit_isCommitUnknown_notSuccess() throws Exception {
        restPost("add_note", "{\"p_id\":900,\"p_body\":\"kill-at-commit\"}")
                .expect(status().is(500))
                .expect(jsonPath("$.code").value("commit_unknown"));
    }

    @Test
    void aSingleRowFunction_answersAnObject_orNull() throws Exception {
        graphql("{ publicFirstNote { id body } publicNoNote { id } }")
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.publicFirstNote.id").value(1))
                .expect(jsonPath("$.data.publicNoNote").doesNotExist());
    }

    @Test
    void aSecurityDefinerFunction_isCalled_andDescribedAsRunningAsItsOwner() throws Exception {
        graphql("{ __type(name: \"Query\") { fields { name description } } }")
                .expect(content().string(containsString("privileges of its owner (SECURITY DEFINER)")));
        graphql("{ publicFirstNote { id owner_id } }")
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.publicFirstNote.owner_id").value("u-1"));
    }

    // ---- GraphQL mutations ----

    @Test
    void aMutationFunction_writes_thenReturnsOnlyTheRowsTheRoleMaySelect() throws Exception {
        graphql("mutation { publicAddNotePair(p_id: 200) { id owner_id } }")
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.publicAddNotePair", hasSize(1)))
                .expect(jsonPath("$.data.publicAddNotePair[0].id").value(200));
        graphqlAs("service", "service", "{ publicNotes(where: { id: { in: [200, 201] } }) { id owner_id } }")
                .expect(jsonPath("$.data.publicNotes", hasSize(2)));
    }

    @Test
    void aMutationFunction_receivesTheSession_andIsNoQueryField() throws Exception {
        graphql("mutation { publicAddNote(p_id: 210, p_body: \"hello\") { id owner_id body } }")
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.publicAddNote[0].owner_id").value("u-1"));
        graphql("{ publicAddNote(p_id: 211, p_body: \"nope\") { id } }")
                .expect(jsonPath("$.errors[0].message", containsString("Unknown field")));
    }

    @Test
    void aMutationFunction_needsAnExplicitPermission() throws Exception {
        permit(userTables(), trackedFunctions(), List.of());

        graphql("mutation { publicAddNote(p_id: 220, p_body: \"x\") { id } }")
                .expect(jsonPath("$.data.publicAddNote").doesNotExist());
        restPost("add_note", "{\"p_id\":220,\"p_body\":\"x\"}").expect(status().isNotFound());
    }

    // ---- REST ----

    @Test
    void restGet_callsAQueryFunction_withArgumentsAndRowParameters() throws Exception {
        restGet("search_notes", "p_query", "alpha%", "select", "id,body")
                .expect(status().isOk())
                .expect(jsonPath("$.data", hasSize(1)))
                .expect(jsonPath("$.data[0].id").value(1))
                .expect(jsonPath("$.data[0].owner_id").doesNotExist());
        restGet("search_notes", "p_query", "%a%", "id", "eq.3")
                .expect(jsonPath("$.data", hasSize(1)))
                .expect(jsonPath("$.data[0].id").value(3))
                .expect(jsonPath("$.data[0].secret").doesNotExist());
        restGet("first_note").expect(jsonPath("$.data.id").value(1));
    }

    @Test
    void restRpc_aSingleRowFunctionWithNoRow_answersDataNull() throws Exception {
        restGet("no_note")
                .expect(status().isOk())
                .expect(content().json("{\"data\":null}", true));
    }

    @Test
    void restPost_callsAQueryFunction_withAJsonBody() throws Exception {
        restPost("search_notes", "{\"p_query\":\"%a%\"}", "order", "id.desc")
                .expect(status().isOk())
                .expect(jsonPath("$.data[*].id", containsInAnyOrder(3, 1)))
                .expect(jsonPath("$.data[0].id").value(3));
        // EXC-610: an rpc refusal has the same shape as every other error: a code, a message, an error.
        restPost("search_notes", "{}").expect(status().isBadRequest())
                .expect(jsonPath("$.code").value("bad_request"))
                .expect(jsonPath("$.message").value(containsString("Missing argument p_query")))
                .expect(jsonPath("$.error").value(containsString("Missing argument p_query")));
    }

    @Test
    void restMutationFunction_isPostOnly_andReturnsOnlyVisibleRows() throws Exception {
        restGet("add_note_pair", "p_id", "300").expect(status().isMethodNotAllowed());
        restPost("add_note_pair", "{\"p_id\":300}")
                .expect(status().isOk())
                .expect(jsonPath("$.data", hasSize(1)))
                .expect(jsonPath("$.data[0].id").value(300));
    }

    // ---- service ----

    @Test
    void service_callsEveryTrackedFunction_withEveryColumn() throws Exception {
        graphqlAs("service", "service", "{ publicSearchNotes(p_query: \"alpha%\") { id secret } }")
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.publicSearchNotes", hasSize(2)));
    }
}
