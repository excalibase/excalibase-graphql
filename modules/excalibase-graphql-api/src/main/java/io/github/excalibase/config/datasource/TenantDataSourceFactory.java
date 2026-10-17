package io.github.excalibase.config.datasource;

import com.zaxxer.hikari.HikariConfig;
import io.github.excalibase.service.DatabaseEngine;
import io.github.excalibase.service.VaultCredentials;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * Builds a tenant's connection pool from its vault record. Under verify-full a Postgres pool
 * authenticates with the record's client certificate and checks the server against
 * the record's CA; a record without that material gets no pool at all. A MySQL pool logs in
 * with its password over TLS (ADR 0041) and always verifies the server's certificate and host
 * against the record's CA alone, whatever {@link TenantDbSslMode} says.
 */
public final class TenantDataSourceFactory implements Function<VaultCredentials, DataSource>, AutoCloseable {

  private static final Runnable NOTHING_TO_RELEASE = () -> { };
  // Sessions in UTC, so a timestamp written without an offset means the same instant on every pool.
  private static final String MYSQL_SESSION_INIT = "SET time_zone = '+00:00'";

  private final TenantDbSslMode sslMode;
  private final int poolSize;
  private final Path root;

  public TenantDataSourceFactory(TenantDbSslMode sslMode, int poolSize) {
    this(sslMode, poolSize, createPrivateRoot());
  }

  TenantDataSourceFactory(TenantDbSslMode sslMode, int poolSize, Path root) {
    this.sslMode = sslMode;
    this.poolSize = poolSize;
    this.root = root;
  }

  @Override
  public DataSource apply(VaultCredentials creds) {
    if (creds.engine() == DatabaseEngine.MYSQL) {
      TenantTrustStore trustStore = TenantTrustStore.write(root, creds.serverCa());
      return pool(() -> mysqlConfig(creds, trustStore), trustStore::close);
    }
    if (!needsCertificates(creds)) {
      return new TenantDataSource(postgresConfig(creds, null), NOTHING_TO_RELEASE);
    }
    TenantTlsFiles files = TenantTlsFiles.write(root, creds.tls());
    return pool(() -> postgresConfig(creds, files), files::close);
  }

  boolean needsCertificates(VaultCredentials creds) {
    return sslMode != TenantDbSslMode.DISABLE && creds.engine() == DatabaseEngine.POSTGRES;
  }

  /** {@code files} is null when {@link #needsCertificates} is false. */
  HikariConfig postgresConfig(VaultCredentials creds, TenantTlsFiles files) {
    HikariConfig config = baseConfig(creds);
    config.addDataSourceProperty("sslmode", sslMode.jdbcValue());
    if (files != null) {
      config.addDataSourceProperty("sslcert", files.clientCertificate().toString());
      config.addDataSourceProperty("sslkey", files.privateKey().toString());
      config.addDataSourceProperty("sslrootcert", files.rootCertificate().toString());
    }
    return config;
  }

  HikariConfig mysqlConfig(VaultCredentials creds, TenantTrustStore trustStore) {
    HikariConfig config = baseConfig(creds);
    config.addDataSourceProperty("sslMode", "VERIFY_IDENTITY");
    config.addDataSourceProperty("trustCertificateKeyStoreUrl", trustStore.file().toUri().toString());
    config.addDataSourceProperty("trustCertificateKeyStoreType", TenantTrustStore.TYPE);
    config.addDataSourceProperty("trustCertificateKeyStorePassword", trustStore.password());
    config.addDataSourceProperty("fallbackToSystemTrustStore", "false");
    config.setConnectionInitSql(MYSQL_SESSION_INIT);
    return config;
  }

  private HikariConfig baseConfig(VaultCredentials creds) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(creds.jdbcUrl());
    config.setUsername(creds.username());
    config.setPassword(creds.password());
    config.setMaximumPoolSize(poolSize);
    config.setMinimumIdle(1);
    config.setConnectionTimeout(10_000);
    config.setIdleTimeout(300_000);
    config.setMaxLifetime(900_000);
    config.setPoolName("tenant-" + creds.host() + "-" + creds.database());
    return config;
  }

  /** The pool owns the written TLS material: released when it closes, or now if it never starts. */
  private static DataSource pool(Supplier<HikariConfig> config, Runnable release) {
    try {
      return new TenantDataSource(config.get(), release);
    } catch (RuntimeException e) {
      release.run();
      throw e;
    }
  }

  Path root() {
    return root;
  }

  @Override
  public void close() {
    TenantTlsFiles.deleteRecursively(root);
  }

  private static Path createPrivateRoot() {
    try {
      return Files.createTempDirectory("excalibase-tenant-tls-",
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot create the private tenant TLS directory", e);
    }
  }
}
