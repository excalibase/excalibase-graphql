package io.github.excalibase.overload;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TomcatConnectionBoundTest {

    private static AbstractHttp11Protocol<?> protocolOf(Connector connector) {
        return (AbstractHttp11Protocol<?>) connector.getProtocolHandler();
    }

    @Test
    @DisplayName("connection limit, backlog and keep-alive rotation are derived from the CPU count")
    void derivedFromCpus() {
        TomcatConnectionBound bound = new TomcatConnectionBound(4, 64, 256, 10, 5000);
        Connector connector = new Connector();
        bound.apply(connector);
        assertThat(protocolOf(connector).getMaxConnections()).isEqualTo(256);
        assertThat(protocolOf(connector).getAcceptCount()).isEqualTo(1024);
        assertThat(protocolOf(connector).getKeepAliveTimeout()).isEqualTo(5000);
    }

    @Test
    @DisplayName("the limit scales with the container: one CPU gets one CPU's share")
    void scalesWithCpus() {
        Connector connector = new Connector();
        new TomcatConnectionBound(1, 64, 256, 10, 5000).apply(connector);
        assertThat(protocolOf(connector).getMaxConnections()).isEqualTo(64);
    }

    @Test
    @DisplayName("non-positive settings are rejected")
    void invalid() {
        assertThatThrownBy(() -> new TomcatConnectionBound(0, 64, 256, 10, 5000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TomcatConnectionBound(1, 0, 256, 10, 5000)).isInstanceOf(IllegalArgumentException.class);
    }
}
