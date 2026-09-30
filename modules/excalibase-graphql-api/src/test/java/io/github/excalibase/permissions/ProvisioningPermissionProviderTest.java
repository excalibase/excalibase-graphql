package io.github.excalibase.permissions;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.excalibase.security.TokenFileSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.github.excalibase.security.UnknownProjectException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The engine's read of a project's permission document (docs/features/permissions.md §8): cached per
 * project, the last good copy served while the control plane errs, an unknown project refused and
 * never cached, and a document that breaks the grammar never applied.
 */
class ProvisioningPermissionProviderTest {

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
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
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
        return new ProvisioningPermissionProvider("http://localhost:" + port + "/api/", token, ttlMillis,
                () -> now[0], meters);
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

    @Test
    void noPermissionSource_refusesEveryRead() {
        PermissionProvider none = new NoPermissionSource();

        assertThatThrownBy(() -> none.permissionsFor("proj1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("no permission source");
        none.evict("proj1");
    }
}
