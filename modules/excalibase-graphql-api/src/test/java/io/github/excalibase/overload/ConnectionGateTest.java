package io.github.excalibase.overload;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConnectionGateTest {

    private final List<String> admitted = new ArrayList<>();

    private ConnectionGate.Waiter waiter(String name) {
        return () -> admitted.add(name);
    }

    @Test
    @DisplayName("connections up to the bound are admitted at once")
    void admitsUpToTheBound() {
        ConnectionGate gate = new ConnectionGate(2);

        assertThat(gate.tryAdmit(waiter("a"))).isEqualTo(ConnectionGate.Outcome.ADMITTED);
        assertThat(gate.tryAdmit(waiter("b"))).isEqualTo(ConnectionGate.Outcome.ADMITTED);
        assertThat(gate.tryAdmit(waiter("c"))).isEqualTo(ConnectionGate.Outcome.QUEUED);
        assertThat(gate.open()).isEqualTo(2);
        assertThat(admitted).isEmpty();
    }

    @Test
    @DisplayName("a closed connection admits the longest waiting one, in order")
    void releaseAdmitsInArrivalOrder() {
        ConnectionGate gate = new ConnectionGate(1, 10);
        gate.tryAdmit(waiter("a"));
        gate.tryAdmit(waiter("b"));
        gate.tryAdmit(waiter("c"));

        gate.release();
        assertThat(admitted).containsExactly("b");
        gate.release();
        assertThat(admitted).containsExactly("b", "c");
        assertThat(gate.open()).isEqualTo(1);
    }

    @Test
    @DisplayName("a waiter that gave up is never admitted and takes no slot")
    void abandonedWaiterIsSkipped() {
        ConnectionGate gate = new ConnectionGate(1, 10);
        gate.tryAdmit(waiter("a"));
        ConnectionGate.Waiter gone = waiter("gone");
        gate.tryAdmit(gone);
        gate.tryAdmit(waiter("c"));

        gate.abandon(gone);
        gate.release();

        assertThat(admitted).containsExactly("c");
    }

    @Test
    @DisplayName("the gate reports when it is full so the listener can stop accepting")
    void reportsFullness() {
        ConnectionGate gate = new ConnectionGate(1);
        assertThat(gate.isFull()).isFalse();
        gate.tryAdmit(waiter("a"));
        assertThat(gate.isFull()).isTrue();
        gate.release();
        assertThat(gate.isFull()).isFalse();
    }

    @Test
    @DisplayName("a connection that finds the wait queue full is refused, not queued, and counted")
    void fullQueueRefuses() {
        ConnectionGate gate = new ConnectionGate(1);
        gate.tryAdmit(waiter("a"));
        assertThat(gate.tryAdmit(waiter("b"))).isEqualTo(ConnectionGate.Outcome.QUEUED);
        assertThat(gate.tryAdmit(waiter("c"))).isEqualTo(ConnectionGate.Outcome.REFUSED);
        assertThat(gate.waiting()).isEqualTo(1);

        gate.recordRefusal();

        assertThat(gate.refused()).isEqualTo(1);
    }

    @Test
    @DisplayName("refusals in progress are capped at the size of the bound, so a flood cannot hold unlimited channels")
    void refusalsAreCapped() {
        ConnectionGate gate = new ConnectionGate(2, 2);

        assertThat(gate.tryBeginRefusal()).isTrue();
        assertThat(gate.tryBeginRefusal()).isTrue();
        assertThat(gate.tryBeginRefusal()).isFalse();
        gate.endRefusal();
        assertThat(gate.tryBeginRefusal()).isTrue();
    }

    @Test
    @DisplayName("a waiting client closes one idle keep-alive connection, once")
    void closesOneIdleConnection() {
        ConnectionGate gate = new ConnectionGate(1, 1);
        List<String> closed = new ArrayList<>();
        gate.offerIdle(() -> closed.add("a"));
        gate.offerIdle(() -> closed.add("b"));

        gate.closeOneIdle();

        assertThat(closed).hasSize(1);
        gate.closeOneIdle();
        assertThat(closed).hasSize(2);
        gate.closeOneIdle();
        assertThat(closed).hasSize(2);
    }

    @Test
    @DisplayName("an idle connection that got busy again is withdrawn and never closed")
    void withdrawnIdleIsKept() {
        ConnectionGate gate = new ConnectionGate(1, 1);
        List<String> closed = new ArrayList<>();
        ConnectionGate.IdleConnection connection = () -> closed.add("a");
        gate.offerIdle(connection);
        gate.withdrawIdle(connection);

        gate.closeOneIdle();

        assertThat(closed).isEmpty();
    }
}
