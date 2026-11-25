package io.github.excalibase;

import com.fasterxml.jackson.databind.JsonNode;
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
import io.github.excalibase.cdc.CDCEvent;
import io.github.excalibase.cdc.SubscriptionService;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
import io.github.excalibase.support.StubControlPlane;
import io.github.excalibase.support.WsClient;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import java.util.Map;

import static io.github.excalibase.PermissionDocs.callable;
import static io.github.excalibase.PermissionDocs.entry;
import static io.github.excalibase.PermissionDocs.everything;
import static io.github.excalibase.PermissionDocs.tracked;
import static io.github.excalibase.support.InternalSchemaProbe.INTROSPECTION;
import static io.github.excalibase.support.InternalSchemaProbe.assertNothingInternal;
import static io.github.excalibase.support.InternalSchemaProbe.fieldsOfTypeWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The engine never exposes its internal schemas (EXC-733): not through GraphQL, REST, the OpenAPI document or
 * realtime, for any role, whatever the permission document grants. The document here names every internal
 * table and function, and the service role is served everything it may be; a reserved object is still absent,
 * as if it did not exist.
 */
class NeverExposeIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "proj-never";
    private static final String APP = "app_authenticated";
    private static final String SERVICE = "service";
    private static final String META_SCHEMA = "excalibase_meta";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-never-expose.sql");
    static final ECPrivateKey privateKey;
    static final StubControlPlane controlPlane;

    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair keyPair = generator.generateKeyPair();
            privateKey = (ECPrivateKey) keyPair.getPrivate();
            String jwks = new JWKSet(new ECKey.Builder(Curve.P_256, (ECPublicKey) keyPair.getPublic())
                    .keyUse(KeyUse.SIGNATURE).keyID("test-key").build()).toString();
            controlPlane = new StubControlPlane()
                    .serveJson("/.well-known/jwks.json", jwks)
                    .serveJson("/api/provision/" + PROJECT + "/permissions/", grantEverything())
                    .start();
        } catch (Exception e) {
            throw new IllegalStateException("control plane stub failed to start", e);
        }
    }

    @AfterAll
    static void stopControlPlane() {
        controlPlane.close();
    }

    /** Every table and function, internal ones included, for the app role; the service role gets the functions. */
    private static String grantEverything() {
        List<String> tables = new ArrayList<>();
        for (String table : List.of("excalibase_meta.t", "auth.users", "public.items", "public.t")) {
            tables.add(entry(table, APP, everything()));
        }
        List<String> functions = new ArrayList<>();
        List<String> callable = new ArrayList<>();
        for (String function : List.of("excalibase_meta.read_t", "auth.read_users", "public.leak_meta",
                "public.plain_items")) {
            functions.add(tracked(function, "QUERY", true, null));
            callable.add(callable(function, APP));
        }
        return PermissionDocs.document(PROJECT, 1, tables, functions, callable);
    }

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>(EngineProperties.postgres(postgres, PROJECT));
        properties.put("app.nats.enabled", "false");
        properties.put("app.security.jwt-enabled", "true");
        properties.put("app.security.insecure-dev-mode", "false");
        properties.put("app.security.auth.jwks-url", controlPlane.url("/.well-known/jwks.json"));
        properties.put("app.security.rls.policy-url", controlPlane.url("/api"));
        properties.put("app.security.rls.policy-pat", "test-pat");
        return properties;
    }

    @Inject
    private SubscriptionService subscriptionService;

    private final List<WsClient> sessions = new ArrayList<>();

    @AfterEach
    void closeSessions() {
        sessions.forEach(WsClient::close);
    }

    private static String jwt(String role) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("u-1").claim("projectId", PROJECT).claim("role", role).claim("scope", SERVICE.equals(role) ? "service" : "authenticated")
                .audience("excalibase:" + PROJECT).issuer("excalibase")
                .issueTime(Date.from(Instant.parse("2024-01-01T00:00:00Z")))
                .expirationTime(Date.from(Instant.parse("2099-01-01T00:00:00Z"))).build();
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), claims);
        signed.sign(new ECDSASigner(privateKey));
        return signed.serialize();
    }

    private HttpAnswer graphql(String role, String query) throws Exception {
        return http.post("/" + PROJECT + "/graphql", MAPPER.writeValueAsString(Map.of("query", query)),
                "Content-Type", "application/json", "Authorization", "Bearer " + jwt(role));
    }

    private HttpAnswer rest(String method, String role, String path, String profile) throws Exception {
        String profileHeader = "GET".equals(method) ? "Accept-Profile" : "Content-Profile";
        return http.send(method, "/" + PROJECT + "/api/v1" + path, "DELETE".equals(method) || "GET".equals(method) ? null : "{\"id\": 9}",
                "Authorization", "Bearer " + jwt(role), "Content-Type", "application/json",
                profileHeader, profile);
    }

    @ParameterizedTest
    @ValueSource(strings = {SERVICE, APP})
    void introspection_namesNoInternalTypeFieldOrFunction(String role) throws Exception {
        HttpAnswer answer = graphql(role, INTROSPECTION);

        assertThat(answer.status()).isEqualTo(200);
        assertNothingInternal(answer.body());
        assertThat(answer.body()).as("a normal table is still served").contains("PublicItems");
    }

    @ParameterizedTest
    @ValueSource(strings = {SERVICE, APP})
    void aForeignKeyIntoAnInternalSchema_isNotARelationship_andDoesNotBindToASameNamedTable(String role)
            throws Exception {
        JsonNode schema = graphql(role, INTROSPECTION).json();

        assertThat(fieldsOfTypeWith(schema, "meta_id")).containsExactlyInAnyOrder("id", "name", "meta_id", "user_id");
        assertThat(fieldsOfTypeWith(schema, "label")).containsExactlyInAnyOrder("id", "label");
    }

    @ParameterizedTest
    @ValueSource(strings = {SERVICE, APP})
    void queriesAndMutationsOnInternalTables_areRejectedAsUnknownFields(String role) throws Exception {
        for (String field : List.of("excalibaseMetaT", "authUsers", "excalibaseMetaTAggregate", "readT",
                "excalibaseMetaReadT", "authReadUsers", "publicLeakMeta")) {
            HttpAnswer answer = graphql(role, "{ " + field + " { id } }");
            assertThat(answer.body()).as(field).contains("errors");
            assertThat(answer.body()).as(field).doesNotContain("secret");
        }
        for (String mutation : List.of("insertExcalibaseMetaT", "insertAuthUsers", "deleteExcalibaseMetaT")) {
            HttpAnswer answer = graphql(role, "mutation { " + mutation + "(input: {id: 9}) { id } }");
            assertThat(answer.body()).as(mutation).contains("errors");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {SERVICE, APP})
    void restOnInternalSchemas_answers404ForEveryMethod(String role) throws Exception {
        for (String[] target : List.of(new String[] {"/t?id=eq.1", META_SCHEMA},
                new String[] {"/users?id=eq.1", "auth"})) {
            for (String method : List.of("GET", "POST", "PATCH", "DELETE")) {
                assertNotFound(rest(method, role, target[0], target[1]), method + " " + target[1] + target[0]);
            }
        }
        for (String[] function : List.of(new String[] {"/rpc/read_t", META_SCHEMA},
                new String[] {"/rpc/read_users", "auth"})) {
            for (String method : List.of("GET", "POST")) {
                assertNotFound(rest(method, role, function[0], function[1]), method + " " + function[1] + function[0]);
            }
        }
    }

    private static void assertNotFound(HttpAnswer answer, String request) {
        assertThat(answer.status()).as(request).isEqualTo(404);
        assertNothingInternal(answer.body());
    }

    @ParameterizedTest
    @ValueSource(strings = {SERVICE, APP})
    void aFunctionReturningInternalRows_isNotCallable(String role) throws Exception {
        assertThat(rest("GET", role, "/rpc/leak_meta", "public").status()).isEqualTo(404);
        assertThat(rest("POST", role, "/rpc/leak_meta", "public").status()).isEqualTo(404);
        HttpAnswer graphql = graphql(role, "{ publicLeakMeta { id } }");
        assertThat(graphql.body()).contains("errors").doesNotContain("secret");
    }

    @ParameterizedTest
    @ValueSource(strings = {SERVICE, APP})
    void theOpenApiDocument_describesNoInternalPath(String role) throws Exception {
        for (String profile : List.of("public", META_SCHEMA, "auth")) {
            HttpAnswer answer = http.get("/" + PROJECT + "/api/v1", "Authorization", "Bearer " + jwt(role),
                    "Accept", "application/openapi+json", "Accept-Profile", profile);
            assertThat(answer.status()).isEqualTo(200);
            assertNothingInternal(answer.body());
        }
    }

    @Test
    void aNormalSchemaIsServedAsBefore() throws Exception {
        assertThat(graphql(APP, "{ publicItems { id name } }").body()).contains("visible");
        assertThat(rest("GET", APP, "/items", "public").body()).contains("visible");
        assertThat(rest("GET", APP, "/rpc/plain_items", "public").body()).contains("visible");
    }

    @Test
    void realtime_refusesInternalTables_andDeliversNoEventOfTheirs() throws Exception {
        WsClient graphqlSession = open("/graphql", List.of("graphql-transport-ws"));
        graphqlSession.sendJson(Map.of("type", "subscribe", "id", "meta", "payload",
                Map.of("query", "subscription { excalibaseMetaTChanges { operation data } }")));
        graphqlSession.sendJson(Map.of("type", "subscribe", "id", "plain", "payload",
                Map.of("query", "subscription { publicTChanges { operation data } }")));
        WsClient restSession = open("/api/v1/realtime", List.of());
        restSession.sendJson(Map.of("type", "subscribe", "id", "meta", "collection", "t", "schema", META_SCHEMA));
        restSession.sendJson(Map.of("type", "subscribe", "id", "users", "collection", "users", "schema", "auth"));
        restSession.sendJson(Map.of("type", "subscribe", "id", "plain", "collection", "t", "schema", "public"));
        assertThat(graphqlSession.await("error").path("id").asText()).isEqualTo("meta");
        assertThat(restSession.await("error").path("id").asText()).isEqualTo("meta");
        assertThat(restSession.await("error").path("id").asText()).isEqualTo("users");
        graphqlSession.send("{\"type\":\"ping\"}");
        graphqlSession.await("pong");
        restSession.sendJson(Map.of("type", "subscribe", "id", "fence", "collection", "no_such_table", "schema", "public"));
        assertThat(restSession.await("error").path("id").asText()).isEqualTo("fence");
        awaitSubscribers("public.t");

        publish(META_SCHEMA, "t", "{\"id\": 1, \"secret\": \"document-secret\"}");
        publish("auth", "users", "{\"id\": 1, \"password_hash\": \"hash-secret\"}");
        publish("public", "t", "{\"id\": 2, \"label\": \"plain\"}");

        assertThat(graphqlSession.await("next").toString()).contains("plain").doesNotContain("secret");
        assertThat(restSession.await("next").toString()).contains("plain").doesNotContain("secret");
    }

    /** An event published before its subscribers are attached is lost, so the test waits for them. */
    private void awaitSubscribers(String table) {
        await().atMost(Duration.ofSeconds(10)).until(() -> subscriptionService.hasSubscribers(PROJECT, table));
    }

    private void publish(String schema, String table, String data) {
        subscriptionService.publish(PROJECT, new CDCEvent("INSERT", schema, table, data, System.currentTimeMillis()));
    }

    private WsClient open(String path, List<String> protocols) throws Exception {
        WsClient session = WsClient.open(URI.create("ws://localhost:" + server.getPort() + "/" + PROJECT + path),
                null, protocols);
        sessions.add(session);
        session.sendJson(Map.of("type", "connection_init", "payload", Map.of("Authorization", "Bearer " + jwt(APP))));
        session.await("connection_ack");
        return session;
    }
}
