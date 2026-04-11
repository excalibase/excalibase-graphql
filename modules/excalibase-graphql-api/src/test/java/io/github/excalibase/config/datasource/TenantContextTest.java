package io.github.excalibase.config.datasource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TenantContextTest {

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("getTenantId returns null when not set")
    void getTenantId_notSet_returnsNull() {
        assertNull(TenantContext.getTenantId());
    }

    @Test
    @DisplayName("setTenantId makes value accessible via getTenantId")
    void setTenantId_accessible() {
        TenantContext.setTenantId("duc-corp/app-a");
        assertEquals("duc-corp/app-a", TenantContext.getTenantId());
    }

    @Test
    @DisplayName("clear removes the tenant id")
    void clear_removesValue() {
        TenantContext.setTenantId("duc-corp/app-a");
        TenantContext.clear();
        assertNull(TenantContext.getTenantId());
    }

    @Test
    @DisplayName("set-clear-set round trip works correctly")
    void setAndClear_roundTrip() {
        TenantContext.setTenantId("tenant-a");
        assertEquals("tenant-a", TenantContext.getTenantId());

        TenantContext.setTenantId("tenant-b");
        assertEquals("tenant-b", TenantContext.getTenantId());

        TenantContext.clear();
        assertNull(TenantContext.getTenantId());
    }

    @Test
    @DisplayName("each thread has isolated tenant context")
    void threadLocal_isolation() throws Exception {
        TenantContext.setTenantId("tenant-main");
        var result = new String[1];

        Thread thread = Thread.ofVirtual().start(() -> {
            // Plain ThreadLocal — child thread does NOT inherit parent value
            result[0] = TenantContext.getTenantId();
        });
        thread.join();

        // Main thread value unaffected
        assertEquals("tenant-main", TenantContext.getTenantId());
        // Child thread had no value
        assertNull(result[0]);
    }
}
