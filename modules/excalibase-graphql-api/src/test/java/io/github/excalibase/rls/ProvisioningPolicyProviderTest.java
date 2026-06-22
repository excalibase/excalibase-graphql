package io.github.excalibase.rls;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ProvisioningPolicyProvider}: it must fetch a project's
 * RLS + CLS policies over HTTP from the provisioning service, map the JSON to
 * the engine's {@link Policy}/{@link ColumnPolicy} records, cache per project
 * with a TTL, and — critically — fail CLOSED when provisioning is unreachable
 * and no policies are cached (returning empty would mean UNRESTRICTED = open).
 */
class ProvisioningPolicyProviderTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger rlsHits = new AtomicInteger();
    private final AtomicInteger colHits = new AtomicInteger();
    private volatile int responseStatus = 200;
    private volatile String authHeaderSeen;

    // Controllable clock so TTL expiry is deterministic (no sleeping).
    private final long[] now = {1_000L};

    private static final String RLS_BODY = """
            [
              {
                "id": "p1",
                "projectId": "proj1",
                "name": "own_orders",
                "resource": "orders",
                "effect": "ALLOW",
                "operations": ["SELECT", "UPDATE"],
                "ruleLogic": "AND",
                "priority": 10,
                "enabled": true,
                "rules": [
                  {"field": "user_id", "fieldType": "INTEGER", "operator": "EQ", "value": "ctx.user_id"}
                ],
                "assignments": [
                  {"targetType": "USER", "targetId": "*"}
                ]
              }
            ]
            """;

    private static final String COL_BODY = """
            [
              {
                "id": "c1",
                "projectId": "proj1",
                "name": "mask_email",
                "resource": "users",
                "columns": ["email"],
                "operations": ["SELECT"],
                "mode": "NULL",
                "priority": 5,
                "enabled": true,
                "assignments": [
                  {"targetType": "ALL"}
                ]
              }
            ]
            """;

    // One column policy per mask mode + every partialSpec variant, so the
    // JSON -> ColumnPolicy mapping (modes + all PartialMaskSpec kinds) is exercised.
    private static final String COL_VARIANTS_BODY = """
            [
              {"id":"m1","name":"hide","resource":"t","columns":["a"],"operations":["SELECT"],"mode":"HIDE","enabled":true,"assignments":[{"targetType":"ALL"}]},
              {"id":"m2","name":"null","resource":"t","columns":["b"],"operations":["SELECT"],"mode":"NULL","enabled":true,"assignments":[{"targetType":"ALL"}]},
              {"id":"m3","name":"hash","resource":"t","columns":["c"],"operations":["SELECT"],"mode":"HASH","enabled":true,"assignments":[{"targetType":"ALL"}]},
              {"id":"m4","name":"custom","resource":"t","columns":["d"],"operations":["SELECT"],"mode":"CUSTOM","customMaskerKey":"k","enabled":true,"assignments":[{"targetType":"ALL"}]},
              {"id":"m5","name":"kf","resource":"t","columns":["e"],"operations":["SELECT"],"mode":"PARTIAL","partialSpec":{"kind":"KEEP_FIRST","n":3,"maskChar":"*"},"enabled":true,"assignments":[{"targetType":"ALL"}]},
              {"id":"m6","name":"kl","resource":"t","columns":["f"],"operations":["SELECT"],"mode":"PARTIAL","partialSpec":{"kind":"KEEP_LAST","n":4,"maskChar":"#"},"enabled":true,"assignments":[{"targetType":"ALL"}]},
              {"id":"m7","name":"kb","resource":"t","columns":["g"],"operations":["SELECT"],"mode":"PARTIAL","partialSpec":{"kind":"KEEP_BOTH","first":2,"last":2,"maskChar":"*"},"enabled":true,"assignments":[{"targetType":"ALL"}]},
              {"id":"m8","name":"mr","resource":"t","columns":["h"],"operations":["SELECT"],"mode":"PARTIAL","partialSpec":{"kind":"MASK_RANGE","start":1,"end":5,"maskChar":"X"},"enabled":true,"assignments":[{"targetType":"ALL"}]},
              {"id":"m9","name":"ss","resource":"t","columns":["i"],"operations":["SELECT"],"mode":"PARTIAL","partialSpec":{"kind":"SUBSTRING","start":0,"length":3,"maskChar":"*"},"enabled":true,"assignments":[{"targetType":"ALL"}]},
              {"id":"m10","name":"rx","resource":"t","columns":["j"],"operations":["SELECT"],"mode":"PARTIAL","partialSpec":{"kind":"REGEX","pattern":".","replacement":"*"},"enabled":true,"assignments":[{"targetType":"ALL"}]}
            ]
            """;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/api/provision/proj1/rls-policies/", exchange -> {
            rlsHits.incrementAndGet();
            authHeaderSeen = exchange.getRequestHeaders().getFirst("Authorization");
            respond(exchange, RLS_BODY);
        });
        server.createContext("/api/provision/proj1/column-policies/", exchange -> {
            colHits.incrementAndGet();
            respond(exchange, COL_BODY);
        });
        server.createContext("/api/provision/proj2/rls-policies/", exchange -> respond(exchange, "[]"));
        server.createContext("/api/provision/proj2/column-policies/", exchange -> respond(exchange, COL_VARIANTS_BODY));
        server.start();
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        if (responseStatus != 200) {
            exchange.sendResponseHeaders(responseStatus, -1);
            exchange.close();
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    @DisplayName("evict drops the cached policies so the next read re-fetches")
    void evictForcesRefetch() {
        ProvisioningPolicyProvider p = provider(60_000);
        p.policiesFor("proj1");
        p.evict("proj1");
        p.policiesFor("proj1");
        assertThat(rlsHits.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("maps every column mask mode and partialSpec kind")
    void mapsColumnPolicyVariants() {
        List<ColumnPolicy> cols = provider(60_000).columnPoliciesFor("proj2");

        assertThat(cols).hasSize(10);
        assertThat(cols.get(0).mode()).isEqualTo(MaskMode.HIDE);
        assertThat(cols.get(1).mode()).isEqualTo(MaskMode.NULL);
        assertThat(cols.get(2).mode()).isEqualTo(MaskMode.HASH);
        assertThat(cols.get(3).mode()).isEqualTo(MaskMode.CUSTOM);
        assertThat(cols.get(3).customMaskerKey()).isEqualTo("k");
        assertThat(cols.get(4).partialSpec()).isInstanceOf(PartialMaskSpec.KeepFirst.class);
        assertThat(((PartialMaskSpec.KeepFirst) cols.get(4).partialSpec()).n()).isEqualTo(3);
        assertThat(cols.get(5).partialSpec()).isInstanceOf(PartialMaskSpec.KeepLast.class);
        assertThat(cols.get(6).partialSpec()).isInstanceOf(PartialMaskSpec.KeepBoth.class);
        assertThat(cols.get(7).partialSpec()).isInstanceOf(PartialMaskSpec.MaskRange.class);
        assertThat(cols.get(8).partialSpec()).isInstanceOf(PartialMaskSpec.Substring.class);
        assertThat(cols.get(9).partialSpec()).isInstanceOf(PartialMaskSpec.Regex.class);
    }

    private ProvisioningPolicyProvider provider(long ttlMillis) {
        // base is the provisioning API root (includes /api), mirroring deployment config
        return new ProvisioningPolicyProvider(
                "http://localhost:" + port + "/api",
                "test-pat",
                ttlMillis,
                () -> now[0]);
    }

    @Test
    @DisplayName("fetches + maps RLS row policies from provisioning JSON")
    void mapsRowPolicies() {
        List<Policy> policies = provider(60_000).policiesFor("proj1");

        assertThat(policies).hasSize(1);
        Policy p = policies.get(0);
        assertThat(p.id()).isEqualTo("p1");
        assertThat(p.resource()).isEqualTo("orders");
        assertThat(p.effect()).isEqualTo(PolicyEffect.ALLOW);
        assertThat(p.operations()).containsExactlyInAnyOrder(Operation.SELECT, Operation.UPDATE);
        assertThat(p.ruleLogic()).isEqualTo(LogicOperator.AND);
        assertThat(p.priority()).isEqualTo(10);
        assertThat(p.enabled()).isTrue();
        assertThat(p.rules()).hasSize(1);
        Rule r = p.rules().get(0);
        assertThat(r.field()).isEqualTo("user_id");
        assertThat(r.fieldType()).isEqualTo(FieldType.INTEGER);
        assertThat(r.operator()).isEqualTo(RuleOperator.EQ);
        assertThat(r.value()).isEqualTo("ctx.user_id");
        assertThat(p.assignments()).hasSize(1);
        assertThat(p.assignments().get(0).targetType()).isEqualTo(TargetType.USER);
    }

    @Test
    @DisplayName("fetches + maps CLS column policies from provisioning JSON")
    void mapsColumnPolicies() {
        List<ColumnPolicy> policies = provider(60_000).columnPoliciesFor("proj1");

        assertThat(policies).hasSize(1);
        ColumnPolicy c = policies.get(0);
        assertThat(c.resource()).isEqualTo("users");
        assertThat(c.columns()).containsExactly("email");
        assertThat(c.mode()).isEqualTo(MaskMode.NULL);
        assertThat(c.assignments().get(0).targetType()).isEqualTo(TargetType.ALL);
    }

    @Test
    @DisplayName("sends the service PAT as a Bearer token")
    void sendsBearerToken() {
        provider(60_000).policiesFor("proj1");
        assertThat(authHeaderSeen).isEqualTo("Bearer test-pat");
    }

    @Test
    @DisplayName("caches within TTL — a second read does not re-hit provisioning")
    void cachesWithinTtl() {
        ProvisioningPolicyProvider p = provider(60_000);
        p.policiesFor("proj1");
        p.policiesFor("proj1");
        assertThat(rlsHits.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("re-fetches after TTL expires")
    void refetchesAfterTtl() {
        ProvisioningPolicyProvider p = provider(30_000);
        p.policiesFor("proj1");
        now[0] += 30_001;            // advance past TTL
        p.policiesFor("proj1");
        assertThat(rlsHits.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("fail-closed: provisioning down + nothing cached → throws (never silently UNRESTRICTED)")
    void failsClosedWhenDownAndNoCache() {
        responseStatus = 503;
        ProvisioningPolicyProvider p = provider(60_000);
        assertThatThrownBy(() -> p.policiesFor("proj1"))
                .isInstanceOf(PolicyFetchException.class);
    }

    @Test
    @DisplayName("stale-while-error: serves last good policies if provisioning goes down after a successful fetch")
    void servesStaleOnErrorAfterSuccess() {
        ProvisioningPolicyProvider p = provider(30_000);
        p.policiesFor("proj1");          // primes cache
        responseStatus = 503;            // provisioning now down
        now[0] += 30_001;                // cache expired, but refresh will fail

        List<Policy> stale = p.policiesFor("proj1");
        assertThat(stale).hasSize(1);    // last good copy, not an exception, not empty
    }
}
