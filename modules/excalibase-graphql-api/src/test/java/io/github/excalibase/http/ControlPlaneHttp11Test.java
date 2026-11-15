package io.github.excalibase.http;

import com.sun.net.httpserver.HttpServer;
import io.github.excalibase.cors.ProvisioningProjectCorsProvider;
import io.github.excalibase.permissions.PermissionCachePolicy;
import io.github.excalibase.permissions.ProvisioningPermissionProvider;
import io.github.excalibase.security.TokenFileSource;
import io.github.excalibase.service.VaultCredentialService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The JDK client offers an HTTP/2 upgrade (h2c) on every plain-http request. Against a Jetty control plane that
 * upgrade occasionally leaves the request unanswered for the server's 30 s idle timeout, and the request timeout
 * does not cut it short (measured: 1 to 3 hangs in 4000 requests, none over HTTP/1.1). Every control-plane client
 * therefore speaks HTTP/1.1 and sends no upgrade.
 */
class ControlPlaneHttp11Test {

    private HttpServer server;
    private String base;
    private final List<String> upgradeOffers = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String upgrade = exchange.getRequestHeaders().getFirst("Upgrade");
            String settings = exchange.getRequestHeaders().getFirst("HTTP2-Settings");
            if (upgrade != null || settings != null) {
                upgradeOffers.add(exchange.getRequestURI().getPath());
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("the permission provider offers no HTTP/2 upgrade")
    void permissionProviderOffersNoUpgrade() {
        var provider = new ProvisioningPermissionProvider(base, new TokenFileSource(null, "token"),
                new PermissionCachePolicy(1_000, 2_000, 1_000), new SimpleMeterRegistry());
        try {
            provider.permissionsFor("p1");
        } catch (RuntimeException expected) {
            // the stub answers 404; only the request head is under test
        }
        assertThat(upgradeOffers).isEmpty();
    }

    @Test
    @DisplayName("the CORS provider offers no HTTP/2 upgrade")
    void corsProviderOffersNoUpgrade() {
        var provider = new ProvisioningProjectCorsProvider(base, "token", 1_000);
        try {
            provider.originsFor("p1");
        } catch (RuntimeException expected) {
            // the stub answers 404; only the request head is under test
        }
        assertThat(upgradeOffers).isEmpty();
    }

    @Test
    @DisplayName("the vault credential client offers no HTTP/2 upgrade")
    void vaultClientOffersNoUpgrade() {
        var service = new VaultCredentialService(base, new TokenFileSource(null, "token"));
        try {
            service.fetchCredentials("acme", "p1");
        } catch (RuntimeException expected) {
            // the stub answers 404; only the request head is under test
        }
        assertThat(upgradeOffers).isEmpty();
    }
}
