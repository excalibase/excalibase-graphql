package io.github.excalibase.permissions;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.excalibase.security.TokenFileSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.github.excalibase.security.UnknownProjectException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The engine's read of a project's permission document (docs/features/permissions.md §8): cached per
 * project, the last good copy served while the control plane errs, an unknown project refused and
 * never cached, a document that breaks the grammar never applied, and a control-plane outage served
 * from the cache only for the max-stale window.
 */
class ProvisioningPermissionProviderTest {

    private static final long MAX_STALE_MS = 300_000;
    private static final long RETRY_INTERVAL_MS = 1_000;

    private static final String DOCUMENT = """
            {
              "projectId": "proj1",
              "version": 7,
              "tables": [
                {
                  "table": "public.orders",
                  "role": "user",
                  "select": { "filter": {"owner_id": {"_eq": "X-Excalibase-User-Id"}}, "columns": ["id","owner_id"], "limit": 100, "allowAggregations": false }
                }
              ],
              "functions": [
                { "function": "public.search_orders", "exposedAs": "QUERY", "inferPermissions": true, "sessionArgument": null }
              ],
              "functionPermissions": []
            }
            """;

    private HttpServer server;
    private int port;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile String body = DOCUMENT;
    private volatile int status = 200;
    private volatile String authorizationSeen;
    private volatile String rawPathSeen;
    private final long[] now = {1_000L};

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/api/provision/proj1/permissions/", this::respond);
        server.start();
        logEvents.start();
        providerLogger.addAppender(logEvents);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        providerLogger.detachAppender(logEvents);
    }

    private final Logger providerLogger = (Logger) LoggerFactory.getLogger(ProvisioningPermissionProvider.class);
    private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();

    private List<String> errorLogs() {
        return logEvents.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private void respond(HttpExchange exchange) throws IOException {
        hits.incrementAndGet();
        authorizationSeen = exchange.getRequestHeaders().getFirst("Authorization");
        rawPathSeen = exchange.getRequestURI().getRawPath();
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

    private ProvisioningPermissionProvider provider(long ttlMillis) {
        return provider(new TokenFileSource(null, "svc-graphql-token"), ttlMillis);
    }

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private ProvisioningPermissionProvider provider(TokenFileSource token, long ttlMillis) {
        return provider(token, ttlMillis, MAX_STALE_MS);
    }

    private ProvisioningPermissionProvider provider(TokenFileSource token, long ttlMillis, long maxStaleMillis) {
        return provider(token, new PermissionCachePolicy(ttlMillis, maxStaleMillis, RETRY_INTERVAL_MS));
    }

    private ProvisioningPermissionProvider provider(TokenFileSource token, PermissionCachePolicy policy) {
        return new ProvisioningPermissionProvider("http://localhost:" + port + "/api/", token, policy,
                () -> now[0], meters);
    }

    private ProvisioningPermissionProvider outageProvider() {
        return provider(new TokenFileSource(null, "svc-graphql-token"), 1_000, 10_000);
    }

    private double fetchFailures() {
        Counter counter = meters.find(ProvisioningPermissionProvider.FETCH_FAILED_METRIC)
                .tag("project", "proj1").counter();
        return counter == null ? 0 : counter.count();
    }

    private double staleSeconds() {
        Gauge gauge = meters.find(ProvisioningPermissionProvider.STALE_SECONDS_METRIC).tag("project", "proj1").gauge();
        assertThat(gauge).as("staleness gauge for proj1").isNotNull();
        return gauge.value();
    }

    private double refusals() {
        Counter counter = meters.find(ProvisioningPermissionProvider.REFUSED_METRIC).tag("project", "proj1").counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void permissionsFor_whenTheDocumentIsValid_returnsItParsed() {
        PermissionSet permissions = provider(60_000).permissionsFor("proj1");

        assertThat(permissions.projectId()).isEqualTo("proj1");
        assertThat(permissions.version()).isEqualTo(7L);
        assertThat(permissions.forRole("user").table("public.orders")).isPresent();
        assertThat(permissions.functions()).hasSize(1);
    }

    @Test
    void permissionsFor_authenticatesWithTheServiceToken() {
        provider(60_000).permissionsFor("proj1");

        assertThat(authorizationSeen).isEqualTo("Bearer svc-graphql-token");
    }

    @Test
    void permissionsFor_readsTheRotatedTokenFile(@TempDir Path dir) throws IOException {
        Path tokenFile = dir.resolve("token");
        Files.writeString(tokenFile, "from-file\n");

        provider(new TokenFileSource(tokenFile.toString(), null), 60_000).permissionsFor("proj1");

        assertThat(authorizationSeen).isEqualTo("Bearer from-file");
    }

    @Test
    void permissionsFor_whenNoTokenResolves_failsWithoutAnUnauthenticatedCall(@TempDir Path dir) {
        ProvisioningPermissionProvider provider =
                provider(new TokenFileSource(dir.resolve("missing").toString(), null), 60_000);

        assertThatThrownBy(() -> provider.permissionsFor("proj1"))
                .isInstanceOf(PermissionsUnavailableException.class);
        assertThat(hits.get()).isZero();
    }

    @Test
    void permissionsFor_withinTheTtl_fetchesOnce() {
        ProvisioningPermissionProvider provider = provider(60_000);

        provider.permissionsFor("proj1");
        provider.permissionsFor("proj1");

        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void permissionsFor_afterTheTtl_fetchesAgain() {
        ProvisioningPermissionProvider provider = provider(1_000);

        provider.permissionsFor("proj1");
        now[0] += 5_000;
        provider.permissionsFor("proj1");

        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    void permissionsFor_whenTheProjectIsUnknown_refusesAndCachesNothing() {
        status = 404;
        ProvisioningPermissionProvider provider = provider(60_000);

        assertThatThrownBy(() -> provider.permissionsFor("proj1")).isInstanceOf(UnknownProjectException.class);
        assertThatThrownBy(() -> provider.permissionsFor("proj1")).isInstanceOf(UnknownProjectException.class);
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    void permissionsFor_whenTheProjectHasGone_dropsTheCachedDocument() {
        ProvisioningPermissionProvider provider = provider(1_000);
        provider.permissionsFor("proj1");

        status = 404;
        now[0] += 5_000;

        assertThatThrownBy(() -> provider.permissionsFor("proj1")).isInstanceOf(UnknownProjectException.class);
        status = 500;
        assertThatThrownBy(() -> provider.permissionsFor("proj1"))
                .isInstanceOf(PermissionsUnavailableException.class);
    }

    @Test
    void permissionsFor_whenTheControlPlaneErrsWithNothingCached_isUnavailable() {
        status = 500;

        assertThatThrownBy(() -> provider(60_000).permissionsFor("proj1"))
                .isInstanceOf(PermissionsUnavailableException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void permissionsFor_whenTheControlPlaneErrsAfterASuccess_servesTheLastGoodDocument() {
        ProvisioningPermissionProvider provider = provider(1_000);
        PermissionSet good = provider.permissionsFor("proj1");

        status = 503;
        now[0] += 5_000;

        assertThat(provider.permissionsFor("proj1")).isEqualTo(good);
    }

    @Test
    void permissionsFor_whenTheControlPlaneIsUnreachable_isUnavailable() {
        server.stop(0);
        server = null;

        assertThatThrownBy(() -> provider(60_000).permissionsFor("proj1"))
                .isInstanceOf(PermissionsUnavailableException.class);
    }

    @Test
    void permissionsFor_whenTheDocumentIsInvalid_isUnavailableAndNotCached() {
        body = DOCUMENT.replace("\"_eq\"", "\"_ilike\"");
        ProvisioningPermissionProvider provider = provider(60_000);

        assertThatThrownBy(() -> provider.permissionsFor("proj1"))
                .isInstanceOf(PermissionsUnavailableException.class)
                .hasCauseInstanceOf(PermissionDocumentException.class);
        body = DOCUMENT;
        assertThat(provider.permissionsFor("proj1").version()).isEqualTo(7L);
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    void permissionsFor_whenAnInvalidDocumentFollowsAGoodOne_keepsServingTheGoodOne() {
        ProvisioningPermissionProvider provider = provider(1_000);
        PermissionSet good = provider.permissionsFor("proj1");

        body = "{\"projectId\":\"proj1\",\"version\":8,\"enforced\":false}";
        now[0] += 5_000;

        assertThat(provider.permissionsFor("proj1")).isEqualTo(good);
    }

    @Test
    void anInvalidDocument_keepsTheGoodOneOnlyForOneTtlFromTheFirstRefusal() {
        ProvisioningPermissionProvider provider = provider(1_000);
        PermissionSet good = provider.permissionsFor("proj1");

        body = "{\"projectId\":\"proj1\",\"version\":8,\"enforced\":false}";
        now[0] += 5_000;
        assertThat(provider.permissionsFor("proj1")).isEqualTo(good);
        now[0] += 999;
        assertThat(provider.permissionsFor("proj1")).isEqualTo(good);
        now[0] += 1;

        assertThatThrownBy(() -> provider.permissionsFor("proj1"))
                .isInstanceOf(PermissionsUnavailableException.class);
        assertThat(refusals()).isEqualTo(3);
    }

    @Test
    void aValidDocumentAfterRefusals_startsTheWindowAfresh() {
        ProvisioningPermissionProvider provider = provider(1_000);
        provider.permissionsFor("proj1");
        body = "{\"projectId\":\"proj1\",\"version\":8,\"enforced\":false}";
        now[0] += 5_000;
        provider.permissionsFor("proj1");

        body = DOCUMENT;
        now[0] += 5_000;
        provider.permissionsFor("proj1");
        body = "{\"projectId\":\"proj1\",\"version\":9,\"enforced\":false}";
        now[0] += 5_000;

        assertThat(provider.permissionsFor("proj1").version()).isEqualTo(7L);
    }

    @Test
    void anUnreachableControlPlane_isNotARefusal() {
        ProvisioningPermissionProvider provider = provider(1_000);
        PermissionSet good = provider.permissionsFor("proj1");

        status = 503;
        now[0] += 50_000;

        assertThat(provider.permissionsFor("proj1")).isEqualTo(good);
        assertThat(refusals()).isZero();
    }

    @Test
    void permissionsFor_whenAKeyIsRepeated_refusesRatherThanPickingOne() {
        body = DOCUMENT.replace("\"columns\": [\"id\",\"owner_id\"]",
                "\"columns\": [\"id\"], \"columns\": \"*\"");

        assertThatThrownBy(() -> provider(60_000).permissionsFor("proj1"))
                .isInstanceOf(PermissionsUnavailableException.class);
    }

    @Test
    void permissionsFor_whenTheBodyIsNotJson_isUnavailable() {
        body = "<html>gateway</html>";

        assertThatThrownBy(() -> provider(60_000).permissionsFor("proj1"))
                .isInstanceOf(PermissionsUnavailableException.class);
    }

    @Test
    void permissionsFor_whenTheDocumentNamesAnotherProject_isUnavailable() {
        body = DOCUMENT.replace("\"projectId\": \"proj1\"", "\"projectId\": \"proj2\"");

        assertThatThrownBy(() -> provider(60_000).permissionsFor("proj1"))
                .isInstanceOf(PermissionsUnavailableException.class)
                .hasMessageContaining("proj2");
    }

    @Test
    void permissionsFor_encodesTheProjectIdIntoOnePathSegment() {
        status = 404;

        assertThatThrownBy(() -> provider(60_000).permissionsFor("proj1/permissions/../x"))
                .isInstanceOf(UnknownProjectException.class);
        assertThat(rawPathSeen).isEqualTo("/api/provision/proj1%2Fpermissions%2F..%2Fx/permissions/");
    }

    @Test
    void permissionsFor_refusesABlankProjectId() {
        assertThatThrownBy(() -> provider(60_000).permissionsFor(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void evict_makesTheNextReadRefetch() {
        ProvisioningPermissionProvider provider = provider(60_000);

        provider.permissionsFor("proj1");
        provider.evict("proj1");
        provider.permissionsFor("proj1");

        assertThat(hits.get()).isEqualTo(2);
    }

    // ---- control-plane outage: the cached copy is served for at most max-stale from the first failure ----

    @Test
    void anOutage_servesTheCachedCopyUntilTheWindowCloses() {
        ProvisioningPermissionProvider provider = outageProvider();
        PermissionSet good = provider.permissionsFor("proj1");

        status = 500;
        now[0] += 5_000;
        assertThat(provider.permissionsFor("proj1")).isEqualTo(good);
        now[0] += 9_999;
        assertThat(provider.permissionsFor("proj1")).isEqualTo(good);
        now[0] += 1;

        assertThatThrownBy(() -> provider.permissionsFor("proj1"))
                .isInstanceOf(PermissionsUnavailableException.class)
                .hasMessageContaining("proj1");
    }

    @Test
    void anUnreachableControlPlane_isBoundedByTheSameWindow() {
        ProvisioningPermissionProvider provider = outageProvider();
        PermissionSet good = provider.permissionsFor("proj1");

        server.stop(0);
        server = null;
        now[0] += 5_000;
        assertThat(provider.permissionsFor("proj1")).isEqualTo(good);
        now[0] += 10_000;

        assertThatThrownBy(() -> provider.permissionsFor("proj1"))
                .isInstanceOf(PermissionsUnavailableException.class);
    }

    @Test
    void aSuccessfulFetch_endsTheOutageAndTheNextOneStartsAfresh() {
        ProvisioningPermissionProvider provider = outageProvider();
        provider.permissionsFor("proj1");
        status = 500;
        now[0] += 5_000;
        provider.permissionsFor("proj1");
        now[0] += 10_000;
        assertThatThrownBy(() -> provider.permissionsFor("proj1"))
                .isInstanceOf(PermissionsUnavailableException.class);

        status = 200;
        now[0] += RETRY_INTERVAL_MS;
        PermissionSet recovered = provider.permissionsFor("proj1");
        status = 500;
        now[0] += 5_000;

        assertThat(provider.permissionsFor("proj1")).isEqualTo(recovered);
    }

    @Test
    void theWindowIsCountedFromTheFirstFailureNotTheLastSuccess() {
        ProvisioningPermissionProvider provider = outageProvider();
        PermissionSet good = provider.permissionsFor("proj1");

        status = 500;
        now[0] += 60_000;

        assertThat(provider.permissionsFor("proj1")).isEqualTo(good);
    }

    @Test
    void everyFailedRefresh_isLoggedAndCountedWithItsStaleness() {
        ProvisioningPermissionProvider provider = outageProvider();
        provider.permissionsFor("proj1");
        status = 500;
        now[0] += 5_000;
        provider.permissionsFor("proj1");
        now[0] += 4_000;
        provider.permissionsFor("proj1");

        assertThat(fetchFailures()).isEqualTo(2);
        assertThat(errorLogs()).containsExactly(
                "permissions_fetch_failed project=proj1 stale_for_ms=0 reason=control plane answered HTTP 500 "
                        + "for the permissions of proj1",
                "permissions_fetch_failed project=proj1 stale_for_ms=4000 reason=control plane answered HTTP 500 "
                        + "for the permissions of proj1");
    }

    @Test
    void theWindowClosing_isLoggedOncePerOutage() {
        ProvisioningPermissionProvider provider = outageProvider();
        provider.permissionsFor("proj1");
        status = 500;
        now[0] += 5_000;
        provider.permissionsFor("proj1");
        now[0] += 10_000;
        assertThatThrownBy(() -> provider.permissionsFor("proj1"))
                .isInstanceOf(PermissionsUnavailableException.class);
        assertThatThrownBy(() -> provider.permissionsFor("proj1"))
                .isInstanceOf(PermissionsUnavailableException.class);

        assertThat(errorLogs())
                .filteredOn(message -> message.startsWith("permissions_stale_expired"))
                .containsExactly("permissions_stale_expired project=proj1 stale_for_ms=10000 max_stale_ms=10000");
        assertThat(fetchFailures()).isEqualTo(2);
    }

    @Test
    void theStalenessGauge_readsZeroWhenFreshAndGrowsThroughAnOutage() {
        ProvisioningPermissionProvider provider = outageProvider();
        provider.permissionsFor("proj1");
        assertThat(staleSeconds()).isZero();

        status = 500;
        now[0] += 5_000;
        provider.permissionsFor("proj1");
        now[0] += 7_000;
        assertThat(staleSeconds()).isEqualTo(7.0);

        status = 200;
        provider.permissionsFor("proj1");
        assertThat(staleSeconds()).isZero();
    }

    @Test
    void anUnknownProject_isNotAFetchFailure() {
        status = 404;

        assertThatThrownBy(() -> outageProvider().permissionsFor("proj1"))
                .isInstanceOf(UnknownProjectException.class);
        assertThat(fetchFailures()).isZero();
        assertThat(errorLogs()).isEmpty();
    }

    @Test
    void aMaxStaleOrRetryIntervalThatIsNotPositive_isRefused() {
        assertThatThrownBy(() -> new PermissionCachePolicy(1_000, 0, 1_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.security.permissions.max-stale-ms");
        assertThatThrownBy(() -> new PermissionCachePolicy(1_000, -1, 1_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PermissionCachePolicy(1_000, 10_000, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.security.permissions.retry-interval-ms");
    }

    // ---- no retry storm: one fetch per project at a time, and at most one attempt per retry interval ----

    private ProvisioningPermissionProvider spacedProvider() {
        return provider(new TokenFileSource(null, "svc-graphql-token"), new PermissionCachePolicy(1_000, 10_000, 2_000));
    }

    @Test
    void concurrentCallers_shareOneFetch() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server.removeContext("/api/provision/proj1/permissions/");
        server.createContext("/api/provision/proj1/permissions/", exchange -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange);
        });
        ProvisioningPermissionProvider provider = outageProvider();
        int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<PermissionSet>> reads = new ArrayList<>();
            for (int caller = 0; caller < callers; caller++) {
                reads.add(pool.submit(() -> provider.permissionsFor("proj1")));
            }
            Thread.sleep(300);
            release.countDown();
            for (Future<PermissionSet> read : reads) {
                assertThat(read.get(5, TimeUnit.SECONDS).version()).isEqualTo(7L);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void duringAnOutage_attemptsAreSpacedByTheRetryInterval() {
        ProvisioningPermissionProvider provider = spacedProvider();
        PermissionSet good = provider.permissionsFor("proj1");
        status = 500;
        now[0] += 5_000;
        provider.permissionsFor("proj1");
        int attempts = hits.get();

        now[0] += 1_999;
        assertThat(provider.permissionsFor("proj1")).isEqualTo(good);
        assertThat(provider.permissionsFor("proj1")).isEqualTo(good);
        assertThat(hits.get()).isEqualTo(attempts);
        assertThat(fetchFailures()).isEqualTo(1);

        now[0] += 1;
        assertThat(provider.permissionsFor("proj1")).isEqualTo(good);
        assertThat(hits.get()).isEqualTo(attempts + 1);
        assertThat(fetchFailures()).isEqualTo(2);
    }

    @Test
    void afterTheWindow_betweenAttempts_refusesWithoutFetching() {
        ProvisioningPermissionProvider provider = spacedProvider();
        provider.permissionsFor("proj1");
        status = 500;
        now[0] += 5_000;
        provider.permissionsFor("proj1");
        now[0] += 10_000;
        assertThatThrownBy(() -> provider.permissionsFor("proj1"))
                .isInstanceOf(PermissionsUnavailableException.class);
        int attempts = hits.get();

        now[0] += 1_000;
        assertThatThrownBy(() -> provider.permissionsFor("proj1"))
                .isInstanceOf(PermissionsUnavailableException.class);

        assertThat(hits.get()).isEqualTo(attempts);
        assertThat(fetchFailures()).isEqualTo(2);
    }

    @Test
    void recovery_happensOnTheNextAttempt() {
        ProvisioningPermissionProvider provider = spacedProvider();
        provider.permissionsFor("proj1");
        status = 500;
        now[0] += 5_000;
        provider.permissionsFor("proj1");
        now[0] += 10_000;
        assertThatThrownBy(() -> provider.permissionsFor("proj1"))
                .isInstanceOf(PermissionsUnavailableException.class);

        status = 200;
        now[0] += 1_000;
        assertThatThrownBy(() -> provider.permissionsFor("proj1"))
                .isInstanceOf(PermissionsUnavailableException.class);
        now[0] += 1_000;

        assertThat(provider.permissionsFor("proj1").version()).isEqualTo(7L);
        assertThat(staleSeconds()).isZero();
    }

    @Test
    void noPermissionSource_refusesEveryRead() {
        PermissionProvider none = new NoPermissionSource();

        assertThatThrownBy(() -> none.permissionsFor("proj1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("no permission source");
        none.evict("proj1");
    }
}
