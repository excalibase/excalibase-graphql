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
        assertThat(ClientAddress.lastForwarded(null)).isNull();
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
}
