package io.github.excalibase.overload;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The admin port is outside the main bound but has a small bound of its own, refused rather than queued. */
class AdminConnectionCapTest {

    private static EmbeddedChannel connect(AtomicInteger open, int cap) {
        KeepAliveRotation rotation = new KeepAliveRotation(new ConnectionBound(1, 1, 1, 100, 5_000), 5_000,
                new AtomicInteger());
        return new EmbeddedChannel(new AdminConnectionCap(open, cap, rotation));
    }

    @Test
    @DisplayName("connections over the cap are closed at once, not queued")
    void overTheCapIsClosed() {
        AtomicInteger open = new AtomicInteger();
        EmbeddedChannel first = connect(open, 2);
        EmbeddedChannel second = connect(open, 2);
        EmbeddedChannel third = connect(open, 2);

        assertThat(first.isOpen()).isTrue();
        assertThat(second.isOpen()).isTrue();
        assertThat(third.isOpen()).isFalse();
        assertThat(open.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("a closed connection gives its place back")
    void closingFreesAPlace() {
        AtomicInteger open = new AtomicInteger();
        EmbeddedChannel first = connect(open, 1);

        first.close();

        assertThat(open.get()).isZero();
        assertThat(connect(open, 1).isOpen()).isTrue();
    }

    @Test
    @DisplayName("a refused connection does not hold a place, however often it is closed")
    void refusedConnectionsAreNotCounted() {
        AtomicInteger open = new AtomicInteger();
        EmbeddedChannel kept = connect(open, 1);
        for (int i = 0; i < 5; i++) {
            connect(open, 1).close();
        }

        assertThat(open.get()).isEqualTo(1);
        assertThat(kept.isOpen()).isTrue();
    }
}
