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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.excalibase.PermissionDocs.entry;
import static io.github.excalibase.PermissionDocs.insert;
import static io.github.excalibase.PermissionDocs.select;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * Nested inserts (docs/features/permissions.md §5) over HTTP on real Postgres, as Hasura runs them: every
 * row obeys its own table's insert permission whatever the role may select, the object relationship's
 * row goes in first and the array relationship's rows last, each statement checked on its own, and one
 * failure anywhere rolls back everything. What comes back is only what the role may select.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class NestedInsertIntegrationTest {

    private static final String PROJECT = "proj-nested-insert";
    private static final String ORDERS = "public.orders";
    private static final String ITEMS = "public.order_items";
    private static final String NOTES = "public.item_notes";
    private static final String CUSTOMERS = "public.customers";

    static final String ANY = "{}";
    static final String ORDER_IS_NEW = "{\"status\":{\"_eq\":\"new\"}}";
    static final String ORDER_HAS_ITEMS = "{\"publicOrderItems\":{}}";
    static final String ITEM_OF_NEW_ORDER = "{\"_and\":[{\"qty\":{\"_gt\":0}},"
            + "{\"publicOrderId\":{\"status\":{\"_eq\":\"new\"}}}]}";

    private static final String ORDER_COLUMNS = "[\"customer_id\",\"note\",\"status\"]";
    private static final String ITEM_COLUMNS = "[\"sku\",\"qty\",\"hidden\"]";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-nested-insert.sql");

    private static final AtomicReference<String> PERMISSIONS = new AtomicReference<>();
    private static final AtomicLong VERSION = new AtomicLong();
    private static ECPrivateKey privateKey;
    private static HttpServer mockVault;
    private static int mockVaultPort;

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
                byte[] payload = jwksJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
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

    @BeforeEach
    void empty() {
        jdbc.execute("TRUNCATE item_notes, order_items, orders, customers RESTART IDENTITY CASCADE");
    }

    static void permit(String... entries) {
        PERMISSIONS.set(PermissionDocs.document(PROJECT, VERSION.incrementAndGet(), entries));
    }

    /** anon may insert orders (source preset) and their items, and read neither. */
    static void anonCheckout(String orderCheck, String itemCheck) {
        permit(entry(ORDERS, "anon", insert(orderCheck, ORDER_COLUMNS, "{\"source\":\"web\"}")),
                entry(ITEMS, "anon", insert(itemCheck, ITEM_COLUMNS, "{}")),
                entry(CUSTOMERS, "anon", insert(ANY, "[\"name\"]", "{}")));
    }

    ResultActions anon(String query) throws Exception {
        return send(post("/" + PROJECT + "/graphql"), query, Map.of());
    }

    private ResultActions as(String role, String query, Map<String, Object> variables) throws Exception {
        return send(post("/" + PROJECT + "/graphql").header("Authorization", "Bearer " + token(role)), query,
                variables);
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String query, Map<String, Object> variables)
            throws Exception {
        return mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("query", query, "variables", variables))));
    }

    private static String token(String role) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("u@test.com").claim("userId", "11111111-1111-1111-1111-111111111111")
                .claim("projectId", PROJECT).audience("excalibase:" + PROJECT)
                .claim("role", role).issuer("excalibase")
                .issueTime(Date.from(Instant.parse("2024-01-01T00:00:00Z")))
                .expirationTime(Date.from(Instant.parse("2099-01-01T00:00:00Z")))
                .build();
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), claims);
        signed.sign(new ECDSASigner(privateKey));
        return signed.serialize();
    }

    static String checkout(String status) {
        return "mutation { createPublicOrders(input: { note: \"checkout\", status: \"" + status + "\","
                + " publicOrderItems: { data: [{ sku: \"a\", qty: 1 }, { sku: \"b\", qty: 2 }] } }) { affected_rows } }";
    }

    int rows(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private void assertNothingWritten() {
        assertThat(rows("orders")).isZero();
        assertThat(rows("order_items")).isZero();
        assertThat(rows("customers")).isZero();
    }

    // ---- insert-only, both ends ----

    @Test
    void anon_submitsAnOrderWithItems_andLearnsOnlyHowManyRowsItWrote() throws Exception {
        anonCheckout(ORDER_IS_NEW, ITEM_OF_NEW_ORDER);

        anon(checkout("new"))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.createPublicOrders.affected_rows").value(3));

        assertThat(jdbc.queryForList("SELECT source FROM orders", String.class)).containsExactly("web");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items i JOIN orders o ON o.id = i.order_id",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void aChildFailingItsCheck_rollsBackTheParentToo() throws Exception {
        anonCheckout(ORDER_IS_NEW, ITEM_OF_NEW_ORDER);

        anon("mutation { createPublicOrders(input: { note: \"x\", publicOrderItems: { data: ["
                + "{ sku: \"a\", qty: 1 }, { sku: \"b\", qty: 0 }] } }) { affected_rows } }")
                .andExpect(jsonPath("$.errors[0].extensions.code").value("permission_check_failed"))
                .andExpect(jsonPath("$.data").doesNotExist());

        assertNothingWritten();
    }

    @Test
    void aChildCheck_seesTheParentInsertedBeforeIt_inTheSameMutation() throws Exception {
        anonCheckout(ANY, ITEM_OF_NEW_ORDER);

        anon(checkout("new")).andExpect(jsonPath("$.data.createPublicOrders.affected_rows").value(3));
        anon(checkout("held")).andExpect(jsonPath("$.errors[0].extensions.code").value("permission_check_failed"));

        assertThat(jdbc.queryForList("SELECT status FROM orders", String.class)).containsExactly("new");
        assertThat(rows("order_items")).isEqualTo(2);
    }

    @Test
    void aParentCheck_doesNotSeeTheChildrenInsertedAfterIt_asInHasura() throws Exception {
        anonCheckout(ORDER_HAS_ITEMS, ANY);

        anon(checkout("new")).andExpect(jsonPath("$.errors[0].extensions.code").value("permission_check_failed"));

        assertNothingWritten();
    }

    @Test
    void aRelationshipTheRoleCannotInsertThrough_isRefused_beforeAnythingIsWritten() throws Exception {
        anonCheckout(ANY, ANY);

        anon("mutation { createPublicOrders(input: { note: \"x\", publicOrderItems: { data: [{ sku: \"a\", qty: 1,"
                + " publicItemNotes: { data: [{ body: \"gift\" }] } }] } }) { affected_rows } }")
                .andExpect(jsonPath("$.errors[0].message", containsString("publicItemNotes")))
                .andExpect(jsonPath("$.data").doesNotExist());

        assertNothingWritten();
    }

    @Test
    void theKeyTheParentFills_cannotBeSentByTheChild() throws Exception {
        permit(entry(ORDERS, "anon", insert(ANY, ORDER_COLUMNS, "{}")),
                entry(ITEMS, "anon", insert(ANY, "[\"order_id\",\"sku\",\"qty\"]", "{}")));

        anon("mutation { createPublicOrders(input: { note: \"x\", publicOrderItems: { data: ["
                + "{ order_id: 1, sku: \"a\", qty: 1 }] } }) { affected_rows } }")
                .andExpect(jsonPath("$.errors[0].message").value(
                        "cannot insert \"order_id\" columns as their values are already being determined by parent insert"));

        assertNothingWritten();
    }

    @Test
    void anObjectRelationship_insertsTheRowItPointsAtFirst() throws Exception {
        anonCheckout(ANY, ANY);

        anon("mutation { createPublicOrders(input: { note: \"x\", publicCustomerId: { data: { name: \"Ann\" } },"
                + " publicOrderItems: { data: [{ sku: \"a\", qty: 1 }] } }) { affected_rows } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.createPublicOrders.affected_rows").value(3));

        assertThat(jdbc.queryForObject("SELECT c.name FROM orders o JOIN customers c ON c.id = o.customer_id",
                String.class)).isEqualTo("Ann");
    }

    // ---- what comes back ----

    @Test
    void aReadableParent_answersItsRow_andHasNoFieldForAnUnreadableChild() throws Exception {
        permit(entry(ORDERS, "clerk", select(ANY, "[\"id\",\"note\"]"), insert(ANY, ORDER_COLUMNS, "{}")),
                entry(ITEMS, "clerk", insert(ANY, ITEM_COLUMNS, "{}")));

        as("clerk", "mutation { createPublicOrders(input: { note: \"kept\", publicOrderItems: { data: ["
                + "{ sku: \"a\", qty: 1 }] } }) { note } }", Map.of())
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.createPublicOrders.note").value("kept"));
        as("clerk", "mutation { createPublicOrders(input: { note: \"peek\", publicOrderItems: { data: ["
                + "{ sku: \"a\", qty: 1 }] } }) { note publicOrderItems { sku } } }", Map.of())
                .andExpect(jsonPath("$.errors[0].message", containsString("Unknown field(s): publicOrderItems")));

        assertThat(jdbc.queryForList("SELECT note FROM orders", String.class)).containsExactly("kept");
        assertThat(rows("order_items")).isEqualTo(1);
    }

    @Test
    void anInsertOnlyParent_answersOnlyTheCount_evenWithReadableChildren() throws Exception {
        permit(entry(ORDERS, "manager", insert(ANY, ORDER_COLUMNS, "{}")),
                entry(ITEMS, "manager", select(ANY, "\"*\""), insert(ANY, ITEM_COLUMNS, "{}")));

        as("manager", checkout("new"), Map.of())
                .andExpect(jsonPath("$.data.createPublicOrders.affected_rows").value(3));
        as("manager", "mutation { createPublicOrders(input: { note: \"x\", publicOrderItems: { data: ["
                + "{ sku: \"a\", qty: 1 }] } }) { id } }", Map.of())
                .andExpect(jsonPath("$.errors[0].message", containsString("Unknown field(s): id")));

        assertThat(rows("orders")).isEqualTo(1);
    }

    @Test
    void aReadableTree_answersTheNewChildren_onlyThoseTheRoleMaySelect() throws Exception {
        permit(entry(ORDERS, "staff", select(ANY, "\"*\""), insert(ANY, ORDER_COLUMNS, "{}")),
                entry(ITEMS, "staff", select("{\"hidden\":{\"_eq\":false}}", "[\"id\",\"order_id\",\"sku\"]"),
                        insert(ANY, ITEM_COLUMNS, "{}")));

        as("staff", "mutation { createPublicOrders(input: { note: \"n\", publicOrderItems: { data: ["
                + "{ sku: \"shown\", qty: 1 }, { sku: \"secret\", qty: 1, hidden: true }] } })"
                + " { note publicOrderItems { sku } } }", Map.of())
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.createPublicOrders.publicOrderItems", hasSize(1)))
                .andExpect(jsonPath("$.data.createPublicOrders.publicOrderItems[0].sku").value("shown"));

        assertThat(rows("order_items")).isEqualTo(2);
    }

    // ---- shapes ----

    @Test
    void createMany_fromVariables_nestsUnderEachRow_andCountsEveryRow() throws Exception {
        anonCheckout(ORDER_IS_NEW, ITEM_OF_NEW_ORDER);
        List<Map<String, Object>> inputs = List.of(
                Map.of("note", "one", "publicOrderItems", Map.of("data", List.of(Map.of("sku", "a", "qty", 1)))),
                Map.of("note", "two", "publicOrderItems", Map.of("data", List.of(Map.of("sku", "b", "qty", 1),
                        Map.of("sku", "c", "qty", 1)))));

        send(post("/" + PROJECT + "/graphql"), "mutation($rows: [PublicOrdersCreateInput!]!) {"
                + " createManyPublicOrders(inputs: $rows) { affected_rows } }", Map.of("rows", inputs))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.createManyPublicOrders.affected_rows").value(5));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items i JOIN orders o ON o.id = i.order_id"
                + " WHERE o.note = 'two'", Integer.class)).isEqualTo(2);
    }

    @Test
    void grandchildren_hangOffTheirOwnParentRow() throws Exception {
        permit(entry(ORDERS, "anon", insert(ANY, ORDER_COLUMNS, "{}")),
                entry(ITEMS, "anon", insert(ANY, ITEM_COLUMNS, "{}")),
                entry(NOTES, "anon", insert("{\"publicItemId\":{\"sku\":{\"_eq\":\"gift\"}}}", "[\"body\"]", "{}")));

        anon("mutation { createPublicOrders(input: { note: \"x\", publicOrderItems: { data: ["
                + "{ sku: \"gift\", qty: 1, publicItemNotes: { data: [{ body: \"wrap it\" }] } },"
                + " { sku: \"plain\", qty: 1 }] } }) { affected_rows } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.createPublicOrders.affected_rows").value(4));

        assertThat(jdbc.queryForObject("SELECT i.sku FROM item_notes n JOIN order_items i ON i.id = n.item_id",
                String.class)).isEqualTo("gift");
    }

    @Test
    void aNestedInsertBesideAnotherField_runsBothInOrder_inOneTransaction() throws Exception {
        anonCheckout(ORDER_IS_NEW, ITEM_OF_NEW_ORDER);

        anon("mutation { first: createPublicCustomers(input: { name: \"Bo\" }) { affected_rows }"
                + " second: createPublicOrders(input: { note: \"x\", publicOrderItems: { data: [{ sku: \"a\", qty: 0 }] } })"
                + " { affected_rows } }")
                .andExpect(jsonPath("$.errors[0].extensions.code").value("permission_check_failed"));
        assertNothingWritten();

        anon("mutation { first: createPublicCustomers(input: { name: \"Bo\" }) { affected_rows }"
                + " second: createPublicOrders(input: { note: \"x\", publicOrderItems: { data: [{ sku: \"a\", qty: 1 }] } })"
                + " { affected_rows } }")
                .andExpect(jsonPath("$.data.first.affected_rows").value(1))
                .andExpect(jsonPath("$.data.second.affected_rows").value(2));
    }

    // ---- parity with native Postgres row security ----

    /** A permission check and the native policy saying the same; both read other tables as the owner would. */
    record Parity(String name, String orderCheck, String orderPolicy, String itemCheck, String itemPolicy,
                  String status) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<Parity> parityCases() {
        String itemPolicy = "qty > 0 AND EXISTS (SELECT 1 FROM orders o WHERE o.id = order_items.order_id"
                + " AND o.status = 'new')";
        String hasItems = "EXISTS (SELECT 1 FROM order_items i WHERE i.order_id = orders.id)";
        return List.of(
                new Parity("child check reads its new parent: passes", ANY, "true", ITEM_OF_NEW_ORDER, itemPolicy, "new"),
                new Parity("child check reads its new parent: fails", ANY, "true", ITEM_OF_NEW_ORDER, itemPolicy, "held"),
                new Parity("parent check reads children inserted after it", ORDER_HAS_ITEMS, hasItems, ANY, "true", "new"),
                new Parity("parent and child checks on their own rows", ORDER_IS_NEW, "status = 'new'", ANY, "true", "new"));
    }

    /**
     * The engine's nested insert succeeds exactly when the same rows, inserted as Hasura orders them (the
     * order, then its items) in one transaction under equivalent native policies, are accepted.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("parityCases")
    void nestedInsert_agreesWithNativeRowSecurity(Parity parity) throws Exception {
        boolean nativeAccepts = nativeAccepts(parity);
        anonCheckout(parity.orderCheck(), parity.itemCheck());

        String body = anon(checkout(parity.status())).andReturn().getResponse().getContentAsString();

        assertThat(!body.contains("\"errors\"")).as(body).isEqualTo(nativeAccepts);
        assertThat(rows("orders")).isEqualTo(nativeAccepts ? 1 : 0);
        assertThat(rows("order_items")).isEqualTo(nativeAccepts ? 2 : 0);
    }

    private static boolean nativeAccepts(Parity parity) throws SQLException {
        DriverManagerDataSource owner = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
        try (Connection connection = owner.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                policies(statement, "orders", parity.orderPolicy());
                policies(statement, "order_items", parity.itemPolicy());
                statement.execute("SET LOCAL ROLE oracle_role");
                insertAsHasuraOrdersIt(connection, parity.status());
                return true;
            } catch (SQLException refused) {
                if (!"42501".equals(refused.getSQLState())) throw refused;
                return false;
            } finally {
                connection.rollback();
            }
        }
    }

    private static void policies(Statement statement, String table, String check) throws SQLException {
        statement.execute("ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY");
        statement.execute("CREATE POLICY oracle_insert ON " + table + " FOR INSERT TO oracle_role WITH CHECK (" + check + ")");
        statement.execute("CREATE POLICY oracle_read ON " + table + " FOR SELECT TO oracle_role USING (true)");
    }

    private static void insertAsHasuraOrdersIt(Connection connection, String status) throws SQLException {
        int orderId;
        try (PreparedStatement order = connection.prepareStatement(
                "INSERT INTO orders (note, status, source) VALUES ('checkout', ?, 'web') RETURNING id")) {
            order.setString(1, status);
            try (ResultSet inserted = order.executeQuery()) {
                inserted.next();
                orderId = inserted.getInt(1);
            }
        }
        try (PreparedStatement items = connection.prepareStatement(
                "INSERT INTO order_items (order_id, sku, qty) VALUES (?, 'a', 1), (?, 'b', 2)")) {
            items.setInt(1, orderId);
            items.setInt(2, orderId);
            items.executeUpdate();
        }
    }
}
