package io.github.excalibase.rls;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exposure grants come down the same wire as the RLS policies, and must obey the
 * same rule: a fetch that fails never turns into "expose everything".
 */
class ProvisioningTableGrantsTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile String body = "";
    private volatile int status = 200;
    private final long[] now = {1_000L};

    private static final String ENFORCED_BODY = """
            {
              "projectId": "proj1",
              "enforced": true,
              "grants": [
                {
                  "id": "g1",
                  "projectId": "proj1",
                  "resource": "public.orders",
                  "operations": ["SELECT", "INSERT"],
                  "role": "authenticated",
                  "enabled": true,
                  "createdAt": "2026-01-01T00:00:00Z",
                  "updatedAt": "2026-01-02T00:00:00Z"
                }
              ]
            }
            """;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/api/provision/proj1/table-grants/", this::respond);
        server.start();
    }

    private void respond(HttpExchange exchange) throws IOException {
        hits.incrementAndGet();
        if (status != 200) {
            exchange.sendResponseHeaders(status, -1);
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

    private ProvisioningPolicyProvider provider(long ttlMillis) {
        return new ProvisioningPolicyProvider("http://localhost:" + port + "/api", "test-pat",
                ttlMillis, () -> now[0]);
    }

    @Test
    void tableGrantsFor_whenEnforcedWithGrants_mapsEveryField() {
        body = ENFORCED_BODY;

        TableGrants grants = provider(60_000).tableGrantsFor("proj1");

        assertThat(grants.enforced()).isTrue();
        assertThat(grants.grants()).hasSize(1);
        TableGrant grant = grants.grants().getFirst();
        assertThat(grant.id()).isEqualTo("g1");
        assertThat(grant.projectId()).isEqualTo("proj1");
        assertThat(grant.resource()).isEqualTo("public.orders");
        assertThat(grant.operations()).containsExactlyInAnyOrder(Operation.SELECT, Operation.INSERT);
        assertThat(grant.role()).isEqualTo("authenticated");
        assertThat(grant.enabled()).isTrue();
    }

    @Test
    void tableGrantsFor_whenEnforcedFalse_doesNotEnforceEvenThoughGrantsAreEmpty() {
        body = """
                {"projectId":"proj1","enforced":false,"grants":[]}
                """;

        assertThat(provider(60_000).tableGrantsFor("proj1").enforced()).isFalse();
    }

    @Test
    void tableGrantsFor_whenEnforcedTrueWithEmptyGrants_enforcesAndExposesNothing() {
        body = """
                {"projectId":"proj1","enforced":true,"grants":[]}
                """;

        TableGrants grants = provider(60_000).tableGrantsFor("proj1");

        assertThat(grants.enforced()).isTrue();
        assertThat(grants.grants()).isEmpty();
    }

    @Test
    void tableGrantsFor_whenEnforcedFieldAbsent_doesNotInferItFromTheGrantList() {
        body = """
                {"projectId":"proj1","grants":[]}
                """;

        assertThat(provider(60_000).tableGrantsFor("proj1").enforced()).isFalse();
    }

    @Test
    void tableGrantsFor_whenOperationsMissing_grantsNothing() {
        body = """
                {"projectId":"proj1","enforced":true,
                 "grants":[{"id":"g1","projectId":"proj1","resource":"public.orders","enabled":true}]}
                """;

        assertThat(provider(60_000).tableGrantsFor("proj1").grants().getFirst().operations()).isEmpty();
    }

    @Test
    void tableGrantsFor_whenEnabledFieldAbsent_defaultsToEnabled() {
        body = """
                {"projectId":"proj1","enforced":true,
                 "grants":[{"id":"g1","resource":"public.orders","operations":["SELECT"]}]}
                """;

        assertThat(provider(60_000).tableGrantsFor("proj1").grants().getFirst().enabled()).isTrue();
    }

    @Test
    void tableGrantsFor_whenCalledTwiceWithinTheTtl_fetchesOnce() {
        body = ENFORCED_BODY;
        ProvisioningPolicyProvider provider = provider(60_000);

        provider.tableGrantsFor("proj1");
        provider.tableGrantsFor("proj1");

        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void tableGrantsFor_whenTheTtlHasPassed_fetchesAgain() {
        body = ENFORCED_BODY;
        ProvisioningPolicyProvider provider = provider(1_000);

        provider.tableGrantsFor("proj1");
        now[0] += 5_000;
        provider.tableGrantsFor("proj1");

        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    void tableGrantsFor_whenTheFetchFailsAfterASuccess_servesTheLastGoodGrants() {
        body = ENFORCED_BODY;
        ProvisioningPolicyProvider provider = provider(1_000);
        provider.tableGrantsFor("proj1");

        status = 500;
        now[0] += 5_000;
        TableGrants stale = provider.tableGrantsFor("proj1");

        assertThat(stale.enforced()).isTrue();
        assertThat(stale.grants()).hasSize(1);
    }

    @Test
    void tableGrantsFor_whenTheFetchFailsWithNothingCached_throwsRatherThanExposingEverything() {
        status = 500;
        ProvisioningPolicyProvider provider = provider(60_000);

        assertThatThrownBy(() -> provider.tableGrantsFor("proj1"))
                .isInstanceOf(PolicyFetchException.class);
    }

    @Test
    void tableGrantsFor_whenProvisioningIsUnreachable_throwsRatherThanExposingEverything() {
        server.stop(0);
        server = null;
        ProvisioningPolicyProvider provider = provider(60_000);

        assertThatThrownBy(() -> provider.tableGrantsFor("proj1"))
                .isInstanceOf(PolicyFetchException.class);
    }

    @Test
    void tableGrantsFor_whenTheEndpointIsNotFound_treatsTheProjectAsNotOptedIn() {
        status = 404;

        assertThat(provider(60_000).tableGrantsFor("proj1").enforced()).isFalse();
    }

    @Test
    void evict_whenCalled_dropsTheCachedGrantsSoTheNextReadRefetches() {
        body = ENFORCED_BODY;
        ProvisioningPolicyProvider provider = provider(60_000);

        provider.tableGrantsFor("proj1");
        provider.evict("proj1");
        provider.tableGrantsFor("proj1");

        assertThat(hits.get()).isEqualTo(2);
    }
}
