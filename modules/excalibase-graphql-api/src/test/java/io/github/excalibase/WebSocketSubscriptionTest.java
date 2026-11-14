package io.github.excalibase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.cdc.CDCEvent;
import io.github.excalibase.cdc.SubscriptionService;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.WsClient;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebSocketSubscriptionTest extends EngineIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init.sql");

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>(EngineProperties.postgres(postgres, "test-proj"));
        properties.put("app.nats.enabled", "false");
        return properties;
    }

    @Inject
    private SubscriptionService subscriptionService;

    private static final ObjectMapper mapper = new ObjectMapper();

    @SuppressWarnings("java:S2925") // Poll loop for async subscription registration in integration test
    private void awaitSubscription(String tableName) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (subscriptionService.hasSubscribers(null, tableName)) return;
            Thread.sleep(100);
        }
        fail("Subscription for '" + tableName + "' not registered within 5 seconds");
    }

    private WsClient connectWebSocket() throws Exception {
        return WsClient.open(URI.create("ws://localhost:" + server.getPort() + "/test-proj/graphql"), null,
                List.of("graphql-transport-ws"));
    }

    private Map<String, Object> parseMessage(String json) throws Exception {
        return mapper.readValue(json, new TypeReference<>() {});
    }

    @Test
    @Order(1)
    @DisplayName("connection_init returns connection_ack")
    void connectionInit_sendsInit_returnsAck() throws Exception {
        WsClient session = connectWebSocket();

        try {
            session.send("{\"type\":\"connection_init\"}");

            String response = session.poll(WAIT);
            assertNotNull(response, "Should receive connection_ack");
            Map<String, Object> msg = parseMessage(response);
            assertEquals("connection_ack", msg.get("type"));
        } finally {
            session.close();
        }
    }

    @Test
    @Order(2)
    @DisplayName("ping returns pong")
    void ping_sendsPing_returnsPong() throws Exception {
        WsClient session = connectWebSocket();

        try {
            // Init first
            session.send("{\"type\":\"connection_init\"}");
            session.poll(WAIT); // consume ack

            session.send("{\"type\":\"ping\"}");

            String response = session.poll(WAIT);
            assertNotNull(response, "Should receive pong");
            Map<String, Object> msg = parseMessage(response);
            assertEquals("pong", msg.get("type"));
        } finally {
            session.close();
        }
    }

    @Test
    @Order(3)
    @DisplayName("subscribe receives CDC events via SubscriptionService")
    void subscribe_publishEvent_receivesNextMessage() throws Exception {
        WsClient session = connectWebSocket();

        try {
            // Init
            session.send("{\"type\":\"connection_init\"}");
            session.poll(WAIT); // ack

            // Subscribe to testSchemaCustomerChanges
            String subscribeMsg = mapper.writeValueAsString(Map.of(
                    "type", "subscribe",
                    "id", "sub-1",
                    "payload", Map.of(
                            "query", "subscription { testSchemaCustomerChanges { operation table data } }"
                    )
            ));
            session.send(subscribeMsg);

            // Wait for subscription to be registered
            awaitSubscription("test_schema.customer");

            // Simulate a CDC event via SubscriptionService
            CDCEvent event = new CDCEvent(
                    "INSERT",
                    "test_schema",
                    "customer",
                    "{\"customer_id\":99,\"first_name\":\"Test\",\"last_name\":\"User\"}",
                    System.currentTimeMillis()
            );
            subscriptionService.publish(null, event);

            // Expect "next" message
            String response = session.poll(WAIT);
            assertNotNull(response, "Should receive next message with CDC event");
            Map<String, Object> msg = parseMessage(response);
            assertEquals("next", msg.get("type"));
            assertEquals("sub-1", msg.get("id"));

            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) msg.get("payload");
            assertNotNull(payload);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) payload.get("data");
            assertNotNull(data);
            @SuppressWarnings("unchecked")
            Map<String, Object> changes = (Map<String, Object>) data.get("testSchemaCustomerChanges");
            assertNotNull(changes);
            assertEquals("INSERT", changes.get("operation"));
            assertEquals("customer", changes.get("table"));
        } finally {
            session.close();
        }
    }

    @Test
    @Order(4)
    @DisplayName("complete cancels subscription")
    @SuppressWarnings("java:S2925") // Thread.sleep waits for WebSocket async cancel to propagate
    void complete_cancelsSubscription_noMoreEvents() throws Exception {
        WsClient session = connectWebSocket();

        try {
            // Init
            session.send("{\"type\":\"connection_init\"}");
            session.poll(WAIT); // ack

            // Subscribe
            String subscribeMsg = mapper.writeValueAsString(Map.of(
                    "type", "subscribe",
                    "id", "sub-2",
                    "payload", Map.of(
                            "query", "subscription { testSchemaCustomerChanges { operation table data } }"
                    )
            ));
            session.send(subscribeMsg);
            awaitSubscription("test_schema.customer");

            // Complete (unsubscribe)
            session.send(mapper.writeValueAsString(Map.of(
                    "type", "complete",
                    "id", "sub-2"
            )));
            Thread.sleep(200);

            // Publish event — should NOT be received
            CDCEvent event = new CDCEvent(
                    "INSERT", "test_schema", "customer",
                    "{\"customer_id\":100}", System.currentTimeMillis()
            );
            subscriptionService.publish(null, event);

            // Should not receive any message
            String response = session.poll(Duration.ofSeconds(1));
            assertNull(response, "Should not receive events after unsubscribe");
        } finally {
            session.close();
        }
    }

    @Test
    @Order(5)
    @DisplayName("multiple subscriptions on different tables")
    void multipleSubscriptions_differentTables_routeCorrectly() throws Exception {
        WsClient session = connectWebSocket();

        try {
            // Init
            session.send("{\"type\":\"connection_init\"}");
            session.poll(WAIT); // ack

            // Subscribe to customer changes
            session.send(mapper.writeValueAsString(Map.of(
                    "type", "subscribe",
                    "id", "sub-customer",
                    "payload", Map.of("query", "subscription { testSchemaCustomerChanges { operation table } }")
            )));

            // Subscribe to orders changes
            session.send(mapper.writeValueAsString(Map.of(
                    "type", "subscribe",
                    "id", "sub-orders",
                    "payload", Map.of("query", "subscription { testSchemaOrdersChanges { operation table } }")
            )));

            awaitSubscription("test_schema.customer");
            awaitSubscription("test_schema.orders");

            // Publish order event — only sub-orders should receive
            subscriptionService.publish(null, new CDCEvent(
                    "UPDATE", "test_schema", "orders",
                    "{\"order_id\":1}", System.currentTimeMillis()
            ));

            String response = session.poll(WAIT);
            assertNotNull(response);
            Map<String, Object> msg = parseMessage(response);
            assertEquals("next", msg.get("type"));
            assertEquals("sub-orders", msg.get("id"));
        } finally {
            session.close();
        }
    }

    @Test
    @Order(6)
    @DisplayName("snake_case table name extracted from camelCase subscription field")
    void snakeCaseTable_camelCaseField_extractsCorrectly() throws Exception {
        WsClient session = connectWebSocket();

        try {
            // Init
            session.send("{\"type\":\"connection_init\"}");
            session.poll(WAIT); // ack

            // Subscribe to testSchemaOrderItemsChanges -> table "order_items"
            session.send(mapper.writeValueAsString(Map.of(
                    "type", "subscribe",
                    "id", "sub-oi",
                    "payload", Map.of("query", "subscription { testSchemaOrderItemsChanges { operation table data } }")
            )));

            awaitSubscription("test_schema.order_items");

            // Publish event for order_items table
            subscriptionService.publish(null, new CDCEvent(
                    "DELETE", "test_schema", "order_items",
                    "{\"order_id\":1,\"product_id\":1}", System.currentTimeMillis()
            ));

            String response = session.poll(WAIT);
            assertNotNull(response, "Should receive event for snake_case table");
            Map<String, Object> msg = parseMessage(response);
            assertEquals("next", msg.get("type"));
            assertEquals("sub-oi", msg.get("id"));

            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) msg.get("payload");
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) payload.get("data");
            @SuppressWarnings("unchecked")
            Map<String, Object> changes = (Map<String, Object>) data.get("testSchemaOrderItemsChanges");
            assertEquals("DELETE", changes.get("operation"));
            assertEquals("order_items", changes.get("table"));
        } finally {
            session.close();
        }
    }

    @Test
    @Order(7)
    @DisplayName("a WebSocket upgrade naming an unknown project is refused")
    void unknownProject_handshakeRefused() {
        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> WsClient.open(URI.create("ws://localhost:" + server.getPort() + "/nonexistent/graphql"),
                        null, List.of()));
        assertEquals(404, WsClient.refusedStatus(refused), String.valueOf(refused.getCause()));
    }
}
