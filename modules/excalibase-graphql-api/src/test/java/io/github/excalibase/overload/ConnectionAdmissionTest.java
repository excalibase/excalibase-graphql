package io.github.excalibase.overload;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A connection waiting for a slot is not read from, so its bytes wait in the kernel as in the accept queue. */
class ConnectionAdmissionTest {

    private static KeepAliveRotation rotation() {
        return new KeepAliveRotation(new ConnectionBound(1, 1, 1, 100, 5_000), 5_000, new AtomicInteger());
    }

    @Test
    @DisplayName("a connection held for a slot has reads switched off, and switched on when it is admitted")
    void heldConnectionIsNotRead() {
        ConnectionGate gate = new ConnectionGate(1);
        EmbeddedChannel first = new EmbeddedChannel(new ConnectionAdmission(gate, rotation()));
        EmbeddedChannel second = new EmbeddedChannel(new ConnectionAdmission(gate, rotation()));

        assertThat(first.config().isAutoRead()).isTrue();
        assertThat(second.config().isAutoRead()).isFalse();

        first.close();
        second.runPendingTasks();

        assertThat(second.config().isAutoRead()).isTrue();
    }

    @Test
    @DisplayName("what a held connection already sent is replayed in order once it is admitted")
    void heldBytesAreReplayed() {
        ConnectionGate gate = new ConnectionGate(1);
        EmbeddedChannel first = new EmbeddedChannel(new ConnectionAdmission(gate, rotation()));
        EmbeddedChannel second = new EmbeddedChannel(new ConnectionAdmission(gate, rotation()));
        second.writeInbound(Unpooled.copiedBuffer("a", java.nio.charset.StandardCharsets.UTF_8));
        assertThat((Object) second.readInbound()).isNull();

        first.close();
        second.runPendingTasks();

        assertThat(second.<io.netty.buffer.ByteBuf>readInbound().toString(java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("a");
    }

    /** Counts the reads that reach the transport, which a server that reads on demand asks for one at a time. */
    private static final class CountingChannel extends EmbeddedChannel {
        private int reads;

        CountingChannel(io.netty.channel.ChannelHandler... handlers) {
            super(false, false, handlers);
            config().setAutoRead(false);
            try {
                register();
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }

        @Override
        protected void doBeginRead() {
            reads++;
        }
    }

    @Test
    @DisplayName("a read asked for by the handlers behind it is held while the connection waits, then made on admission")
    void onDemandReadsAreHeld() {
        ConnectionGate gate = new ConnectionGate(1);
        CountingChannel first = new CountingChannel(new ConnectionAdmission(gate, rotation()));
        CountingChannel second = new CountingChannel(new ConnectionAdmission(gate, rotation()));
        first.pipeline().read();
        second.pipeline().read();

        assertThat(first.reads).isEqualTo(1);
        assertThat(second.reads).isZero();

        first.close();
        second.runPendingTasks();

        assertThat(second.reads).isEqualTo(1);
        assertThat(second.config().isAutoRead()).as("the setting the connection arrived with is restored").isFalse();
    }
}
