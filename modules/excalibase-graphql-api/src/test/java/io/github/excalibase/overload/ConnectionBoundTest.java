package io.github.excalibase.overload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConnectionBoundTest {

    @Test
    @DisplayName("connection limit, backlog and keep-alive rotation are derived from the CPU count")
    void derivedFromCpus() {
        ConnectionBound bound = new ConnectionBound(4, 64, 256, 10, 5000);

        assertThat(bound.maxConnections()).isEqualTo(256);
        assertThat(bound.acceptBacklog()).isEqualTo(1024);
        assertThat(bound.maxKeepAliveRequests()).isEqualTo(10);
        assertThat(bound.keepAliveTimeoutMillis()).isEqualTo(5000);
    }

    @Test
    @DisplayName("the limit scales with the container: one CPU gets one CPU's share")
    void scalesWithCpus() {
        assertThat(new ConnectionBound(1, 64, 256, 10, 5000).maxConnections()).isEqualTo(64);
        assertThat(new ConnectionBound(8, 64, 256, 10, 5000).maxConnections()).isEqualTo(512);
    }

    @Test
    @DisplayName("non-positive settings are rejected")
    void invalid() {
        assertThatThrownBy(() -> new ConnectionBound(0, 64, 256, 10, 5000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConnectionBound(1, 0, 256, 10, 5000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConnectionBound(1, 64, 0, 10, 5000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConnectionBound(1, 64, 256, 0, 5000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConnectionBound(1, 64, 256, 10, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a limit that overflows int fails at startup with a message naming the setting")
    void overflow() {
        assertThatThrownBy(() -> new ConnectionBound(64, Integer.MAX_VALUE, 256, 10, 5000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-connections-per-cpu");
        assertThatThrownBy(() -> new ConnectionBound(64, 256, Integer.MAX_VALUE, 10, 5000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("accept-count-per-cpu");
    }

    @Test
    @DisplayName("the wait queue follows the CPUs and defaults to the connection bound")
    void waitQueue() {
        assertThat(new ConnectionBound(2, 64, 256, 10, 5000).maxWaiting()).isEqualTo(128);
        assertThat(new ConnectionBound(2, 64, 256, 10, 5000, 250, 8).maxWaiting()).isEqualTo(16);
        assertThatThrownBy(() -> new ConnectionBound(2, 64, 256, 10, 5000, 250, -1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("wait-queue-per-cpu");
    }
}
