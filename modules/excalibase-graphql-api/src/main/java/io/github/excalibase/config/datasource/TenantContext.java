package io.github.excalibase.config.datasource;

public final class TenantContext {

  public static final ScopedValue<String> TENANT_ID = ScopedValue.newInstance();

  private TenantContext() {}

  public static String getTenantId() {
    return TENANT_ID.isBound() ? TENANT_ID.get() : null;
  }
}
