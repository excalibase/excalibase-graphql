package io.github.excalibase;

import com.fasterxml.jackson.databind.DeserializationFeature;
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
import io.github.excalibase.support.StubControlPlane;
import io.github.excalibase.support.WsClient;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Realtime is a live view: a change reaches every subscriber whose select permission lets them see the
 * row, and no one else, for a delete as for an insert. Real websocket clients, a real Postgres (the
 * deleted rows are really gone when their change arrives) and change events shaped as the watcher
 * publishes them for a table with REPLICA IDENTITY FULL.
 */
class RealtimeDeleteIntegrationTest extends EngineIntegrationTest {

    private static final String ALICE = "11111111-1111-1111-1111-111111111111";
    private static final String BOB = "22222222-2222-2222-2222-222222222222";
    private static final String PROJECT = "proj-rt";
    private static final String ROLE = "app_authenticated";
    private static final String SCHEMA = "rls_demo";

    private static final String PERMISSIONS = PermissionDocs.document(PROJECT,
            PermissionDocs.entry("rls_demo.notes", ROLE,
                    PermissionDocs.select(PermissionDocs.OWNED, "[\"id\",\"owner_id\"]")),
            PermissionDocs.entry("rls_demo.orders", ROLE, PermissionDocs.select(
                    "{\"rlsDemoOrgId\":{\"rlsDemoMembers\":{\"member_user\":{\"_eq\":\"X-Excalibase-User-Id\"}}}}",
                    "\"*\"")),
            PermissionDocs.entry("rls_demo.ledger", ROLE,
                    PermissionDocs.select("{\"amount\":{\"_gt\":100}}", "[\"id\",\"amount\"]")));

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-engine-rls.sql");

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
                    .serveJson("/api/provision/" + PROJECT + "/permissions/", PERMISSIONS)
                    .start();
        } catch (Exception e) {
            throw new IllegalStateException("control plane stub failed to start", e);
        }
    }

    @AfterAll
    static void stopControlPlane() {
        controlPlane.close();
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

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @Inject
    private SubscriptionService subscriptionService;

    private final List<WsClient> sessions = new ArrayList<>();

    @AfterEach
    void closeSessions() {
        sessions.forEach(WsClient::close);
    }

    private static void update(String sql, Object... parameters) throws SQLException {
        try (Connection connection = postgres.createConnection("");
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.executeUpdate();
        }
    }

    @Test
    void aDelete_reachesOnlyTheSubscriberWhoCouldSeeTheRow_withOnlyTheirColumns() throws Exception {
        Subscriber alice = graphqlSubscriber(ALICE, "rlsDemoNotesChanges");
        Subscriber bob = graphqlSubscriber(BOB, "rlsDemoNotesChanges");
        update("INSERT INTO rls_demo.notes VALUES (201, ?::uuid, 'alice secret'), (202, ?::uuid, 'bob secret')",
                ALICE, BOB);
        update("DELETE FROM rls_demo.notes WHERE id IN (201, 202)");

        publish("DELETE", "notes", "{\"id\": 201, \"owner_id\": \"" + ALICE + "\", \"title\": \"alice secret\"}");
        publish("DELETE", "notes", "{\"id\": 202, \"owner_id\": \"" + BOB + "\", \"title\": \"bob secret\"}");

        JsonNode aliceChange = alice.nextChange();
        assertThat(aliceChange.get("operation").asText()).isEqualTo("DELETE");
        assertThat(MAPPER.convertValue(aliceChange.get("data"), Map.class)).isEqualTo(Map.of("id", "201", "owner_id", ALICE));
        assertThat(bob.nextChange().get("data").get("id").asInt()).isEqualTo(202);
        publish("INSERT", "notes", "{\"id\": 203, \"owner_id\": \"" + ALICE + "\", \"title\": \"t\"}");
        assertThat(alice.nextChange().get("data").get("id").asInt()).as("Bob's delete never reached Alice").isEqualTo(203);
    }

    @Test
    void aKeyOnlyDelete_reachesNoOne() throws Exception {
        Subscriber alice = graphqlSubscriber(ALICE, "rlsDemoNotesChanges");

        publish("DELETE", "notes", "{\"id\": 204}");
        publish("DELETE", "notes", "{\"id\": 205, \"owner_id\": \"" + ALICE + "\", \"title\": \"t\"}");

        assertThat(alice.nextChange().get("data").get("id").asInt()).isEqualTo(205);
    }

    @Test
    void anOwnershipTransfer_showsEachSubscriberOnlyTheImageTheyCouldSee() throws Exception {
        Subscriber alice = graphqlSubscriber(ALICE, "rlsDemoNotesChanges");
        Subscriber bob = graphqlSubscriber(BOB, "rlsDemoNotesChanges");

        publish("UPDATE", "notes", "{\"old\": {\"id\": 206, \"owner_id\": \"" + ALICE + "\", \"title\": \"t\"},"
                + " \"new\": {\"id\": 206, \"owner_id\": \"" + BOB + "\", \"title\": \"t\"}}");

        assertThat(alice.nextChange().get("data").fieldNames()).toIterable().containsExactly("old");
        assertThat(bob.nextChange().get("data").fieldNames()).toIterable().containsExactly("new");
    }

    @Test
    void aDeleteUnderARelationshipFilter_isJudgedAfterTheRowIsGone() throws Exception {
        Subscriber alice = graphqlSubscriber(ALICE, "rlsDemoOrdersChanges");
        Subscriber bob = graphqlSubscriber(BOB, "rlsDemoOrdersChanges");
        update("INSERT INTO rls_demo.orders VALUES (301, 'orgA', 'a'), (302, 'orgB', 'b')");
        update("DELETE FROM rls_demo.orders WHERE id IN (301, 302)");

        publish("DELETE", "orders", "{\"id\": 301, \"org_id\": \"orgA\", \"title\": \"a\"}");
        publish("DELETE", "orders", "{\"id\": 302, \"org_id\": \"orgB\", \"title\": \"b\"}");

        assertThat(alice.nextChange().get("data").get("id").asInt()).isEqualTo(301);
        assertThat(bob.nextChange().get("data").get("id").asInt()).isEqualTo(302);
    }

    @Test
    void aNumericFilter_judgesTheExactValue() throws Exception {
        Subscriber alice = graphqlSubscriber(ALICE, "rlsDemoLedgerChanges");

        publish("DELETE", "ledger", "{\"id\": 401, \"amount\": 100.00, \"created_at\": \"2026-10-01 00:00:00+00\"}");
        publish("DELETE", "ledger", "{\"id\": 402, \"amount\": 100.01, \"created_at\": \"2026-10-01 00:00:00+00\"}");

        JsonNode change = alice.nextChange();
        assertThat(change.get("data").get("id").asInt()).isEqualTo(402);
        assertThat(change.get("data").get("amount").isTextual()).as("a decimal arrives as the string a query returns").isTrue();
        assertThat(change.get("data").get("amount").asText()).isEqualTo("100.01");
    }

    @Test
    void aGraphqlChange_readsLikeAGraphqlQuery_bigintAsAStringAndNullKept() throws Exception {
        Subscriber alice = graphqlSubscriber(ALICE, "rlsDemoOrdersChanges");

        publish("INSERT", "orders", "{\"id\": 1, \"org_id\": \"orgA\", \"title\": null}");

        JsonNode data = alice.nextChange().get("data");
        assertThat(data.get("id").isTextual()).as("bigint arrives as the string a query returns").isTrue();
        assertThat(data.get("id").asText()).isEqualTo("1");
        assertThat(data.has("title")).as("a null column is sent, not dropped").isTrue();
        assertThat(data.get("title").isNull()).isTrue();
    }

    @Test
    void aRestRealtimeChange_readsLikeRest_bigintAsAStringAndNullKept() throws Exception {
        Subscriber alice = realtimeSubscriber(ALICE, "orders");

        publish("INSERT", "orders", "{\"id\": 1, \"org_id\": \"orgA\", \"title\": null}");

        JsonNode doc = alice.nextMessage().get("doc");
        assertThat(doc.get("id").isTextual()).as("bigint arrives as the string REST returns").isTrue();
        assertThat(doc.get("id").asText()).isEqualTo("1");
        assertThat(doc.has("title")).as("a null column is sent, not dropped").isTrue();
        assertThat(doc.get("title").isNull()).isTrue();
    }

    @Test
    void theRestRealtimeEndpoint_deliversADeleteOnlyToItsOwner() throws Exception {
        Subscriber alice = realtimeSubscriber(ALICE, "notes");
        Subscriber bob = realtimeSubscriber(BOB, "notes");

        publish("DELETE", "notes", "{\"id\": 207, \"owner_id\": \"" + ALICE + "\", \"title\": \"t\"}");
        publish("DELETE", "notes", "{\"id\": 208, \"owner_id\": \"" + BOB + "\", \"title\": \"t\"}");

        JsonNode aliceMessage = alice.nextMessage();
        assertThat(aliceMessage.get("op").asText()).isEqualTo("delete");
        assertThat(aliceMessage.get("doc").fieldNames()).toIterable().containsExactlyInAnyOrder("id", "owner_id");
        assertThat(bob.nextMessage().get("doc").get("id").asInt()).isEqualTo(208);
    }

    private void publish(String type, String table, String data) {
        subscriptionService.publish(PROJECT, new CDCEvent(type, SCHEMA, table, data, System.currentTimeMillis()));
    }

    /** Subscribed once {@code ping} is answered: the server handles a session's messages in order. */
    private Subscriber graphqlSubscriber(String userId, String field) throws Exception {
        Subscriber subscriber = connect(userId, "/graphql", List.of("graphql-transport-ws"));
        subscriber.send(Map.of("type", "subscribe", "id", "sub", "payload",
                Map.of("query", "subscription { " + field + " { operation table data } }")));
        subscriber.send(Map.of("type", "ping"));
        subscriber.await("pong");
        subscriber.field = field;
        return subscriber;
    }

    /** Subscribed once a second, refused subscription is answered, for the same ordering reason. */
    private Subscriber realtimeSubscriber(String userId, String collection) throws Exception {
        Subscriber subscriber = connect(userId, "/api/v1/realtime", List.of());
        subscriber.send(Map.of("type", "subscribe", "id", "sub", "collection", collection, "schema", SCHEMA));
        subscriber.send(Map.of("type", "subscribe", "id", "fence", "collection", "no_such_table", "schema", SCHEMA));
        subscriber.await("error");
        return subscriber;
    }

    private Subscriber connect(String userId, String path, List<String> protocols) throws Exception {
        WsClient session = WsClient.open(URI.create("ws://localhost:" + server.getPort() + "/" + PROJECT + path),
                null, protocols);
        sessions.add(session);
        Subscriber subscriber = new Subscriber(session);
        subscriber.send(Map.of("type", "connection_init", "payload", Map.of("Authorization", "Bearer " + jwt(userId))));
        subscriber.await("connection_ack");
        return subscriber;
    }

    private static String jwt(String userId) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(String.valueOf(userId))
                .claim("projectId", PROJECT)
                .audience("excalibase:" + PROJECT)
                .claim("role", ROLE)
                .issuer("excalibase")
                .issueTime(Date.from(Instant.parse("2024-01-01T00:00:00Z")))
                .expirationTime(Date.from(Instant.parse("2099-01-01T00:00:00Z")))
                .build();
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), claims);
        signed.sign(new ECDSASigner(privateKey));
        return signed.serialize();
    }

    private static final class Subscriber {

        private final WsClient session;
        private String field;

        Subscriber(WsClient session) {
            this.session = session;
        }

        void send(Map<String, Object> message) throws Exception {
            session.sendJson(message);
        }

        JsonNode await(String type) throws Exception {
            return session.await(type);
        }

        /** The next change delivered on the GraphQL subscription. */
        JsonNode nextChange() throws Exception {
            return await("next").get("payload").get("data").get(field);
        }

        /** The next change delivered on the REST realtime subscription. */
        JsonNode nextMessage() throws Exception {
            return await("next");
        }
    }
}
