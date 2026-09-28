package io.github.excalibase.config.datasource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** A tenant pool that also releases what it was built from (its certificate files) when closed. */
final class TenantDataSource extends HikariDataSource {

  private final Runnable release;

  TenantDataSource(HikariConfig config, Runnable release) {
    super(config);
    this.release = release;
  }

  @Override
  public void close() {
    super.close();
    release.run();
  }
}
