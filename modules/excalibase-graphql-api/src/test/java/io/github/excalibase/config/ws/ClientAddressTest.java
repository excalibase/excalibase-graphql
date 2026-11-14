package io.github.excalibase.config.ws;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;

class ClientAddressTest {

    @Test
    @DisplayName("the last X-Forwarded-For entry is the address the proxy saw")
    void lastForwardedEntry() {
        assertThat(ClientAddress.lastForwarded("6.6.6.6, 203.0.113.9")).isEqualTo("203.0.113.9");
        assertThat(ClientAddress.lastForwarded("203.0.113.9")).isEqualTo("203.0.113.9");
        assertThat(ClientAddress.lastForwarded(" ")).isNull();
        assertThat(ClientAddress.lastForwarded((String) null)).isNull();
        assertThat(ClientAddress.lastForwarded("x".repeat(100))).isNull();
    }

    @Test
    @DisplayName("only a private-network peer is trusted to forward an address")
    void internalPeers() throws Exception {
        assertThat(ClientAddress.isInternal(InetAddress.getByName("10.1.2.3"))).isTrue();
        assertThat(ClientAddress.isInternal(InetAddress.getByName("192.168.0.4"))).isTrue();
        assertThat(ClientAddress.isInternal(InetAddress.getByName("127.0.0.1"))).isTrue();
        assertThat(ClientAddress.isInternal(InetAddress.getByName("fd00::1"))).isTrue();
        assertThat(ClientAddress.isInternal(InetAddress.getByName("203.0.113.9"))).isFalse();
        assertThat(ClientAddress.isInternal(InetAddress.getByName("2001:db8::1"))).isFalse();
    }

    private static io.micronaut.http.HttpRequest<?> from(String peer, String forwardedFor) throws Exception {
        io.micronaut.http.MutableHttpRequest<?> real = io.micronaut.http.HttpRequest.GET("/p/graphql");
        if (forwardedFor != null) {
            real.header("X-Forwarded-For", forwardedFor);
        }
        io.micronaut.http.HttpRequest<?> request = org.mockito.Mockito.mock(io.micronaut.http.HttpRequest.class);
        org.mockito.Mockito.when(request.getHeaders()).thenReturn(real.getHeaders());
        org.mockito.Mockito.when(request.getRemoteAddress())
                .thenReturn(new java.net.InetSocketAddress(InetAddress.getByName(peer), 40000));
        return request;
    }

    @Test
    @DisplayName("IPv6 clients are counted by their /64, so one host cannot take a new address per socket")
    void ipv6ByPrefix() throws Exception {
        String first = ClientAddress.of(from("2001:db8:1:2:aaaa::1", null));
        String second = ClientAddress.of(from("2001:db8:1:2:bbbb:cccc:dddd:eeee", null));
        String other = ClientAddress.of(from("2001:db8:1:3::1", null));
        assertThat(first).isEqualTo(second).isNotEqualTo(other);
    }

    @Test
    @DisplayName("a forwarded IPv6 address is counted by its /64 too, and IPv4 stays exact")
    void forwardedIpv6ByPrefix() throws Exception {
        assertThat(ClientAddress.of(from("10.0.0.5", "2001:db8:1:2:1::1")))
                .isEqualTo(ClientAddress.of(from("10.0.0.6", "2001:db8:1:2:2::2")));
        assertThat(ClientAddress.of(from("203.0.113.9", null))).isEqualTo("203.0.113.9");
        assertThat(ClientAddress.of(from("10.0.0.5", "203.0.113.7"))).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("with two X-Forwarded-For lines the proxy's own, the last, decides; the client's first line cannot")
    void lastHeaderLineWins() throws Exception {
        io.micronaut.http.MutableHttpRequest<?> real = io.micronaut.http.HttpRequest.GET("/p/graphql");
        real.header("X-Forwarded-For", "6.6.6.6");
        real.header("X-Forwarded-For", "203.0.113.9");
        io.micronaut.http.HttpRequest<?> request = org.mockito.Mockito.mock(io.micronaut.http.HttpRequest.class);
        org.mockito.Mockito.when(request.getHeaders()).thenReturn(real.getHeaders());
        org.mockito.Mockito.when(request.getRemoteAddress())
                .thenReturn(new java.net.InetSocketAddress(InetAddress.getByName("10.0.0.5"), 40000));
        assertThat(ClientAddress.of(request)).isEqualTo("203.0.113.9");
    }
}
