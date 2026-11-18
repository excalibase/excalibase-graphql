package io.github.excalibase.overload;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InternalPortsTest {

    @Test
    void onlyTheManagementPortIsInternalWhileTheConsoleIsOff() {
        assertThat(OverloadConfig.internalPorts(8081, false, 10001)).containsExactlyInAnyOrder(8081);
    }

    @Test
    void theAdminPortIsInternalToo_soAFullMainPortNeverLocksTheOwnerOut() {
        assertThat(OverloadConfig.internalPorts(8081, true, 10001)).containsExactlyInAnyOrder(8081, 10001);
    }
}
