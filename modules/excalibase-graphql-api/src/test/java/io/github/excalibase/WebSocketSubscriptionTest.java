package io.github.excalibase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.cdc.CDCEvent;
import io.github.excalibase.cdc.SubscriptionService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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

import java.net.URI;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebSocketSubscriptionTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.schemas", () -> "test_schema");
        registry.add("app.max-rows", () -> 30);
        registry.add("app.nats.enabled", () -> false);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private SubscriptionService subscriptionService;

    private static final ObjectMapper mapper = new ObjectMapper();

    private void awaitSubscription(String tableName) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (subscriptionService.hasSubscribers(tableName)) return;
            Thread.sleep(100);
        }
        fail("Subscription for '" + tableName + "' not registered within 5 seconds");
    }

    private WebSocketSession connectWebSocket(BlockingQueue<String> messages) throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setSecWebSocketProtocol(java.util.List.of("graphql-transport-ws"));

        CompletableFuture<WebSocketSession> futureSession = client.execute(
                new TextWebSocketHandler() {
                    @Override
                    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                        messages.add(message.getPayload());
                    }
                },
                headers,
                URI.create("ws://localhost:" + port + "/graphql")
        );

        return futureSession.get(5, TimeUnit.SECONDS);
    }

    private Map<String, Object> parseMessage(String json) throws Exception {
        return mapper.readValue(json, new TypeReference<>() {});
    }

    @Test
    @Order(1)
    @DisplayName("connection_init returns connection_ack")
    void connectionInit_sendsInit_returnsAck() throws Exception {
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        WebSocketSession session = connectWebSocket(messages);

        try {
            session.sendMessage(new TextMessage("{\"type\":\"connection_init\"}"));

            String response = messages.poll(5, TimeUnit.SECONDS);
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
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        WebSocketSession session = connectWebSocket(messages);

        try {
            // Init first
            session.sendMessage(new TextMessage("{\"type\":\"connection_init\"}"));
            messages.poll(5, TimeUnit.SECONDS); // consume ack

            session.sendMessage(new TextMessage("{\"type\":\"ping\"}"));

            String response = messages.poll(5, TimeUnit.SECONDS);
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
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        WebSocketSession session = connectWebSocket(messages);

        try {
            // Init
            session.sendMessage(new TextMessage("{\"type\":\"connection_init\"}"));
            messages.poll(5, TimeUnit.SECONDS); // ack

            // Subscribe to testSchemaCustomerChanges
            String subscribeMsg = mapper.writeValueAsString(Map.of(
                    "type", "subscribe",
                    "id", "sub-1",
                    "payload", Map.of(
                            "query", "subscription { testSchemaCustomerChanges { operation table data } }"
                    )
            ));
            session.sendMessage(new TextMessage(subscribeMsg));

            // Wait for subscription to be registered
            awaitSubscription("test_schema_customer");

            // Simulate a CDC event via SubscriptionService
            CDCEvent event = new CDCEvent(
                    "INSERT",
                    "test_schema",
                    "customer",
                    "{\"customer_id\":99,\"first_name\":\"Test\",\"last_name\":\"User\"}",
                    System.currentTimeMillis()
            );
            subscriptionService.publish(event);

            // Expect "next" message
            String response = messages.poll(5, TimeUnit.SECONDS);
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
    void complete_cancelsSubscription_noMoreEvents() throws Exception {
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        WebSocketSession session = connectWebSocket(messages);

        try {
            // Init
            session.sendMessage(new TextMessage("{\"type\":\"connection_init\"}"));
            messages.poll(5, TimeUnit.SECONDS); // ack

            // Subscribe
            String subscribeMsg = mapper.writeValueAsString(Map.of(
                    "type", "subscribe",
                    "id", "sub-2",
                    "payload", Map.of(
                            "query", "subscription { testSchemaCustomerChanges { operation table data } }"
                    )
            ));
            session.sendMessage(new TextMessage(subscribeMsg));
            awaitSubscription("test_schema_customer");

            // Complete (unsubscribe)
            session.sendMessage(new TextMessage(mapper.writeValueAsString(Map.of(
                    "type", "complete",
                    "id", "sub-2"
            ))));
            Thread.sleep(200);

            // Publish event — should NOT be received
            CDCEvent event = new CDCEvent(
                    "INSERT", "test_schema", "customer",
                    "{\"customer_id\":100}", System.currentTimeMillis()
            );
            subscriptionService.publish(event);

            // Should not receive any message
            String response = messages.poll(1, TimeUnit.SECONDS);
            assertNull(response, "Should not receive events after unsubscribe");
        } finally {
            session.close();
        }
    }

    @Test
    @Order(5)
    @DisplayName("multiple subscriptions on different tables")
    void multipleSubscriptions_differentTables_routeCorrectly() throws Exception {
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        WebSocketSession session = connectWebSocket(messages);

        try {
            // Init
            session.sendMessage(new TextMessage("{\"type\":\"connection_init\"}"));
            messages.poll(5, TimeUnit.SECONDS); // ack

            // Subscribe to customer changes
            session.sendMessage(new TextMessage(mapper.writeValueAsString(Map.of(
                    "type", "subscribe",
                    "id", "sub-customer",
                    "payload", Map.of("query", "subscription { testSchemaCustomerChanges { operation table } }")
            ))));

            // Subscribe to orders changes
            session.sendMessage(new TextMessage(mapper.writeValueAsString(Map.of(
                    "type", "subscribe",
                    "id", "sub-orders",
                    "payload", Map.of("query", "subscription { testSchemaOrdersChanges { operation table } }")
            ))));

            awaitSubscription("test_schema_customer");
            awaitSubscription("test_schema_orders");

            // Publish order event — only sub-orders should receive
            subscriptionService.publish(new CDCEvent(
                    "UPDATE", "test_schema", "orders",
                    "{\"order_id\":1}", System.currentTimeMillis()
            ));

            String response = messages.poll(5, TimeUnit.SECONDS);
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
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        WebSocketSession session = connectWebSocket(messages);

        try {
            // Init
            session.sendMessage(new TextMessage("{\"type\":\"connection_init\"}"));
            messages.poll(5, TimeUnit.SECONDS); // ack

            // Subscribe to testSchemaOrderItemsChanges -> table "order_items"
            session.sendMessage(new TextMessage(mapper.writeValueAsString(Map.of(
                    "type", "subscribe",
                    "id", "sub-oi",
                    "payload", Map.of("query", "subscription { testSchemaOrderItemsChanges { operation table data } }")
            ))));

            awaitSubscription("test_schema_order_items");

            // Publish event for order_items table
            subscriptionService.publish(new CDCEvent(
                    "DELETE", "test_schema", "order_items",
                    "{\"order_id\":1,\"product_id\":1}", System.currentTimeMillis()
            ));

            String response = messages.poll(5, TimeUnit.SECONDS);
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
}
