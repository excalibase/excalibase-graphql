package io.github.excalibase.config.datasource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TenantContextTest {

  @Test
  @DisplayName("getTenantId returns null when not bound")
  void getTenantId_notBound_returnsNull() {
    assertNull(TenantContext.getTenantId());
  }

  @Test
  @DisplayName("ScopedValue is accessible within where().run() scope")
  void scopedValue_accessibleWithinScope() {
    ScopedValue.where(TenantContext.TENANT_ID, "duc-corp/app-a").run(() -> {
      assertEquals("duc-corp/app-a", TenantContext.getTenantId());
    });
  }

  @Test
  @DisplayName("ScopedValue is not accessible after scope exits")
  void scopedValue_notAccessibleAfterScope() {
    ScopedValue.where(TenantContext.TENANT_ID, "duc-corp/app-a").run(() -> {
      assertNotNull(TenantContext.getTenantId());
    });
    assertNull(TenantContext.getTenantId());
  }

  @Test
  @DisplayName("nested scopes see their own value")
  void scopedValue_nestedScopes() {
    ScopedValue.where(TenantContext.TENANT_ID, "tenant-a").run(() -> {
      assertEquals("tenant-a", TenantContext.getTenantId());

      ScopedValue.where(TenantContext.TENANT_ID, "tenant-b").run(() -> {
        assertEquals("tenant-b", TenantContext.getTenantId());
      });

      // outer scope restored
      assertEquals("tenant-a", TenantContext.getTenantId());
    });
  }

  @Test
  @DisplayName("each virtual thread has its own scoped value")
  void scopedValue_threadIsolation() throws Exception {
    var result = new String[1];

    ScopedValue.where(TenantContext.TENANT_ID, "tenant-main").run(() -> {
      Thread thread = Thread.ofVirtual().start(() -> {
        // child thread does NOT inherit ScopedValue (unlike InheritableThreadLocal)
        result[0] = TenantContext.getTenantId();
      });
      try { thread.join(); } catch (InterruptedException e) { throw new RuntimeException(e); }

      assertEquals("tenant-main", TenantContext.getTenantId());
    });

    assertNull(result[0]);
  }
}
