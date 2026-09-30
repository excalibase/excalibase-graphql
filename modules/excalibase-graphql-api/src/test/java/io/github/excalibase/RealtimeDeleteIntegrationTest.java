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
import com.sun.net.httpserver.HttpServer;
import io.github.excalibase.cdc.CDCEvent;
import io.github.excalibase.cdc.SubscriptionService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Realtime is a live view: a change reaches every subscriber whose select permission lets them see the
 * row, and no one else, for a delete as for an insert. Real websocket clients, a real Postgres (the
 * deleted rows are really gone when their change arrives) and change events shaped as the watcher
 * publishes them for a table with REPLICA IDENTITY FULL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RealtimeDeleteIntegrationTest {

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

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-engine-rls.sql");

    static ECPrivateKey privateKey;
    static HttpServer controlPlane;

    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair keyPair = generator.generateKeyPair();
            privateKey = (ECPrivateKey) keyPair.getPrivate();
            controlPlane = HttpServer.create(new InetSocketAddress(0), 0);
            String jwks = new JWKSet(new ECKey.Builder(Curve.P_256, (ECPublicKey) keyPair.getPublic())
                    .keyUse(KeyUse.SIGNATURE).keyID("test-key").build()).toString();
            controlPlane.createContext("/.well-known/jwks.json", exchange -> {
                byte[] body = jwks.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            PermissionDocs.serve(controlPlane, PROJECT, () -> PERMISSIONS);
            controlPlane.start();
        } catch (Exception e) {
            throw new IllegalStateException("control plane stub failed to start", e);
        }
    }

    @AfterAll
    static void stopControlPlane() {
        controlPlane.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.database-type", () -> "postgres");
        registry.add("app.nats.enabled", () -> false);
        registry.add("app.security.jwt-enabled", () -> "true");
        registry.add("app.project-id", () -> PROJECT);
        registry.add("app.security.auth.jwks-url",
                () -> "http://localhost:" + controlPlane.getAddress().getPort() + "/.well-known/jwks.json");
        registry.add("app.security.rls.policy-url",
                () -> "http://localhost:" + controlPlane.getAddress().getPort() + "/api");
        registry.add("app.security.rls.policy-pat", () -> "test-pat");
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @LocalServerPort
    private int port;

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<WebSocketSession> sessions = new ArrayList<>();

    @AfterEach
    void closeSessions() throws Exception {
        for (WebSocketSession session : sessions) {
            session.close();
        }
    }

    @Test
    void aDelete_reachesOnlyTheSubscriberWhoCouldSeeTheRow_withOnlyTheirColumns() throws Exception {
        Subscriber alice = graphqlSubscriber(ALICE, "rlsDemoNotesChanges");
        Subscriber bob = graphqlSubscriber(BOB, "rlsDemoNotesChanges");
        jdbc.update("INSERT INTO rls_demo.notes VALUES (201, ?::uuid, 'alice secret'), (202, ?::uuid, 'bob secret')",
                ALICE, BOB);
        jdbc.update("DELETE FROM rls_demo.notes WHERE id IN (201, 202)");

        publish("DELETE", "notes", "{\"id\": 201, \"owner_id\": \"" + ALICE + "\", \"title\": \"alice secret\"}");
        publish("DELETE", "notes", "{\"id\": 202, \"owner_id\": \"" + BOB + "\", \"title\": \"bob secret\"}");

        JsonNode aliceChange = alice.nextChange();
        assertThat(aliceChange.get("operation").asText()).isEqualTo("DELETE");
        assertThat(MAPPER.convertValue(aliceChange.get("data"), Map.class)).isEqualTo(Map.of("id", 201, "owner_id", ALICE));
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
        jdbc.update("INSERT INTO rls_demo.orders VALUES (301, 'orgA', 'a'), (302, 'orgB', 'b')");
        jdbc.update("DELETE FROM rls_demo.orders WHERE id IN (301, 302)");

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
        assertThat(change.get("data").get("amount").decimalValue()).isEqualTo(new BigDecimal("100.01"));
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
        BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setSecWebSocketProtocol(protocols);
        WebSocketSession session = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession ignored, TextMessage message) throws Exception {
                received.add(MAPPER.readTree(message.getPayload()));
            }
        }, headers, URI.create("ws://localhost:" + port + "/" + PROJECT + path)).get(10, TimeUnit.SECONDS);
        sessions.add(session);
        Subscriber subscriber = new Subscriber(session, received);
        subscriber.send(Map.of("type", "connection_init", "payload", Map.of("Authorization", "Bearer " + jwt(userId))));
        subscriber.await("connection_ack");
        return subscriber;
    }

    private static String jwt(String userId) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("user@test.com")
                .claim("userId", userId)
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

        private final WebSocketSession session;
        private final BlockingQueue<JsonNode> received;
        private String field;

        Subscriber(WebSocketSession session, BlockingQueue<JsonNode> received) {
            this.session = session;
            this.received = received;
        }

        void send(Map<String, Object> message) throws Exception {
            session.sendMessage(new TextMessage(MAPPER.writeValueAsString(message)));
        }

        JsonNode await(String type) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                JsonNode message = received.poll(200, TimeUnit.MILLISECONDS);
                if (message != null && type.equals(message.path("type").asText())) {
                    return message;
                }
            }
            return fail("no " + type + " message within 10s");
        }

        /** The next change delivered on the GraphQL subscription. */
        JsonNode nextChange() throws InterruptedException {
            return await("next").get("payload").get("data").get(field);
        }

        /** The next change delivered on the REST realtime subscription. */
        JsonNode nextMessage() throws InterruptedException {
            return await("next");
        }
    }
}
