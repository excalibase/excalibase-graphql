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

        assertThat(gate.tryAdmit(waiter("a"))).isTrue();
        assertThat(gate.tryAdmit(waiter("b"))).isTrue();
        assertThat(gate.tryAdmit(waiter("c"))).isFalse();
        assertThat(gate.open()).isEqualTo(2);
        assertThat(admitted).isEmpty();
    }

    @Test
    @DisplayName("a closed connection admits the longest waiting one, in order")
    void releaseAdmitsInArrivalOrder() {
        ConnectionGate gate = new ConnectionGate(1);
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
        ConnectionGate gate = new ConnectionGate(1);
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
}
