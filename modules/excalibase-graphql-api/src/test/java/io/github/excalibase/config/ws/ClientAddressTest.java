package io.github.excalibase.config.ws;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

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

    private static String of(String peer, String... forwardedLines) throws Exception {
        return ClientAddress.of(new InetSocketAddress(InetAddress.getByName(peer), 40000),
                forwardedLines.length == 0 ? null : List.of(forwardedLines));
    }

    @Test
    @DisplayName("a public peer cannot choose its address with X-Forwarded-For")
    void publicPeerIsNotTrustedToForward() throws Exception {
        assertThat(of("203.0.113.9", "6.6.6.6")).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("IPv6 clients are counted by their /64, so one host cannot take a new address per socket")
    void ipv6ByPrefix() throws Exception {
        String first = of("2001:db8:1:2:aaaa::1");
        String second = of("2001:db8:1:2:bbbb:cccc:dddd:eeee");
        String other = of("2001:db8:1:3::1");
        assertThat(first).isEqualTo(second).isNotEqualTo(other);
    }

    @Test
    @DisplayName("a forwarded IPv6 address is counted by its /64 too, and IPv4 stays exact")
    void forwardedIpv6ByPrefix() throws Exception {
        assertThat(of("10.0.0.5", "2001:db8:1:2:1::1")).isEqualTo(of("10.0.0.6", "2001:db8:1:2:2::2"));
        assertThat(of("203.0.113.9")).isEqualTo("203.0.113.9");
        assertThat(of("10.0.0.5", "203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("with two X-Forwarded-For lines the proxy's own, the last, decides; the client's first line cannot")
    void lastHeaderLineWins() throws Exception {
        MockHttpServletRequest servlet = new MockHttpServletRequest("GET", "/p/graphql");
        servlet.setRemoteAddr("10.0.0.5");
        servlet.setRemoteHost("10.0.0.5");
        servlet.setRemotePort(40000);
        servlet.addHeader("X-Forwarded-For", "6.6.6.6");
        servlet.addHeader("X-Forwarded-For", "203.0.113.9");

        assertThat(ClientAddress.of(new ServletServerHttpRequest(servlet))).isEqualTo("203.0.113.9");
    }
}
