package io.github.excalibase.cors;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Behind the edge proxy the engine's own origin is the one the proxy saw, but only a private-network peer may say so. */
class ForwardedOriginTest {

    private static HttpRequest<?> from(String peer, String... headers) throws Exception {
        MutableHttpRequest<?> real = HttpRequest.GET("/p/graphql");
        for (int i = 0; i + 1 < headers.length; i += 2) {
            real.header(headers[i], headers[i + 1]);
        }
        HttpRequest<?> request = mock(HttpRequest.class);
        when(request.getHeaders()).thenReturn(real.getHeaders());
        when(request.getRemoteAddress()).thenReturn(new InetSocketAddress(InetAddress.getByName(peer), 40000));
        when(request.isSecure()).thenReturn(false);
        return request;
    }

    @Test
    @DisplayName("an https page of the API's own host is same-origin when the proxy says it spoke https")
    void proxyHttpsIsSameOrigin() throws Exception {
        HttpRequest<?> request = from("10.0.0.5", "Host", "api.example.com", "X-Forwarded-Proto", "https");
        assertThat(CorsFilter.sameOrigin(request, "https://api.example.com")).isTrue();
        assertThat(CorsFilter.sameOrigin(request, "http://api.example.com")).isFalse();
    }

    @Test
    @DisplayName("the proxy's forwarded host is the engine's own host")
    void proxyHostIsSameOrigin() throws Exception {
        HttpRequest<?> request = from("10.0.0.5", "Host", "engine.internal:10000",
                "X-Forwarded-Proto", "https", "X-Forwarded-Host", "api.example.com");
        assertThat(CorsFilter.sameOrigin(request, "https://api.example.com")).isTrue();
    }

    @Test
    @DisplayName("a client outside the private network cannot choose its scheme or host")
    void publicPeerIsNotTrusted() throws Exception {
        HttpRequest<?> request = from("203.0.113.9", "Host", "api.example.com",
                "X-Forwarded-Proto", "https", "X-Forwarded-Host", "evil.example");
        assertThat(CorsFilter.sameOrigin(request, "https://api.example.com")).isFalse();
        assertThat(CorsFilter.sameOrigin(request, "https://evil.example")).isFalse();
        assertThat(CorsFilter.sameOrigin(request, "http://api.example.com")).isTrue();
    }

    @Test
    @DisplayName("a forwarded scheme other than http or https is ignored")
    void oddSchemeIgnored() throws Exception {
        HttpRequest<?> request = from("10.0.0.5", "Host", "api.example.com", "X-Forwarded-Proto", "gopher");
        assertThat(CorsFilter.sameOrigin(request, "http://api.example.com")).isTrue();
    }

    @Test
    @DisplayName("with two forwarded lines the proxy's, the last, decides; the client's first line cannot")
    void lastHeaderLineWins() throws Exception {
        HttpRequest<?> request = from("10.0.0.5", "Host", "engine.internal",
                "X-Forwarded-Proto", "http", "X-Forwarded-Proto", "https",
                "X-Forwarded-Host", "evil.example", "X-Forwarded-Host", "api.example.com");
        assertThat(CorsFilter.sameOrigin(request, "https://api.example.com")).isTrue();
        assertThat(CorsFilter.sameOrigin(request, "https://evil.example")).isFalse();
    }
}
