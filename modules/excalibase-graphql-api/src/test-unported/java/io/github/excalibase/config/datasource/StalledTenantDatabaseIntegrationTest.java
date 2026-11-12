package io.github.excalibase.config.datasource;

import io.github.excalibase.service.DatabaseEngine;
import io.github.excalibase.service.VaultCredentials;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.images.builder.Transferable;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * EXC-650: after an in-place restore or failover a pooled connection to the replaced server must
 * fail the request within the read cap, not hold it until the edge gives up, and the next request
 * must reach the new server. Each database sits behind a proxy that stops forwarding under a warm pool.
 */
class StalledTenantDatabaseIntegrationTest {

  private static final String ROLE = "excalibase_app";
  private static final String PASSWORD = "role-password";
  private static final Duration READ_CAP = Duration.ofSeconds(3);
  private static final Duration HANG = Duration.ofSeconds(40);

  private static PostgreSQLContainer<?> postgres;
  private static MySQLContainer<?> mysql;
  private static TestPki pki;

  @TempDir
  Path root;

  @BeforeAll
  static void startDatabases(@TempDir Path pkiDir) throws Exception {
    pki = TestPki.generate(pkiDir, ROLE, "DNS:localhost");
    postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    mysql = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("app")
        .withCopyToContainer(Transferable.of(pki.serverCertificate), "/tls/server.crt")
        .withCopyToContainer(Transferable.of(pki.serverKey), "/tls/server.key")
        .withCommand("--ssl-cert=/tls/server.crt", "--ssl-key=/tls/server.key");
    postgres.start();
    mysql.start();
    try (Connection admin = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
         Statement statement = admin.createStatement()) {
      statement.execute("CREATE USER '" + ROLE + "'@'%' IDENTIFIED BY '" + PASSWORD + "' REQUIRE SSL");
      statement.execute("GRANT SELECT ON app.* TO '" + ROLE + "'@'%'");
    }
  }

  @AfterAll
  static void stopDatabases() {
    if (postgres != null) {
      postgres.stop();
    }
    if (mysql != null) {
      mysql.stop();
    }
  }

  @Test
  @DisplayName("a Postgres project's request fails within the read cap when the server stops answering, then reaches the new one")
  void postgresFailsFastThenReconnects() throws Exception {
    try (StallingProxy proxy = new StallingProxy(postgres.getHost(), postgres.getFirstMappedPort())) {
      VaultCredentials record = new VaultCredentials("localhost", String.valueOf(proxy.port()),
          postgres.getDatabaseName(), postgres.getUsername(), postgres.getPassword());
      assertFailsFastThenReconnects(proxy, record);
    }
  }

  @Test
  @DisplayName("a MySQL project's request fails within the read cap when the server stops answering, then reaches the new one")
  void mysqlFailsFastThenReconnects() throws Exception {
    try (StallingProxy proxy = new StallingProxy(mysql.getHost(), mysql.getFirstMappedPort())) {
      VaultCredentials record = new VaultCredentials("localhost", String.valueOf(proxy.port()), "app",
          ROLE, PASSWORD, null, DatabaseEngine.MYSQL, pki.caCertificate);
      assertFailsFastThenReconnects(proxy, record);
    }
  }

  @Test
  @DisplayName("the single-database pool fails within the read cap when its server stops answering, then reconnects")
  void singleDatabaseFailsFastThenReconnects() throws Exception {
    try (StallingProxy proxy = new StallingProxy(postgres.getHost(), postgres.getFirstMappedPort())) {
      DataSourceProperties properties = new DataSourceProperties();
      properties.setUrl("jdbc:postgresql://localhost:" + proxy.port() + "/" + postgres.getDatabaseName());
      properties.setUsername(postgres.getUsername());
      properties.setPassword(postgres.getPassword());
      assertFailsFastThenReconnects(proxy, DataSourceConfig.singleDatabasePool(properties, READ_CAP));
    }
  }

  @Test
  @DisplayName("a query that runs past the read cap is a request error, not a hang")
  void slowQueryIsBoundedByTheReadCap() throws Exception {
    TenantDataSourceFactory factory = new TenantDataSourceFactory(TenantDbSslMode.DISABLE, 2, root, READ_CAP);
    DataSource dataSource = factory.apply(new VaultCredentials(postgres.getHost(),
        String.valueOf(postgres.getFirstMappedPort()), postgres.getDatabaseName(),
        postgres.getUsername(), postgres.getPassword()));
    try {
      long started = System.nanoTime();
      Object outcome = within(() -> selectOne(dataSource, "SELECT pg_sleep(10)"));
      assertInstanceOf(SQLException.class, outcome);
      assertTrue(elapsed(started).compareTo(Duration.ofSeconds(8)) < 0, "failed only after " + elapsed(started));
    } finally {
      ((AutoCloseable) dataSource).close();
    }
  }

  private void assertFailsFastThenReconnects(StallingProxy proxy, VaultCredentials record) throws Exception {
    TenantDataSourceFactory factory = new TenantDataSourceFactory(TenantDbSslMode.DISABLE, 2, root, READ_CAP);
    assertFailsFastThenReconnects(proxy, factory.apply(record));
  }

  private static void assertFailsFastThenReconnects(StallingProxy proxy, DataSource dataSource) throws Exception {
    try {
      assertEquals(1, within(() -> selectOne(dataSource, "SELECT 1")), "warm the pool");

      // Straight after the last use, as on a busy pool: no liveness check runs on borrow.
      proxy.stall();
      long started = System.nanoTime();
      Object stalled = within(() -> selectOne(dataSource, "SELECT 1"));
      assertInstanceOf(SQLException.class, stalled, "a stalled server answered");
      assertTrue(elapsed(started).compareTo(Duration.ofSeconds(15)) < 0, "failed only after " + elapsed(started));

      proxy.resume();
      started = System.nanoTime();
      assertEquals(1, within(() -> selectOne(dataSource, "SELECT 1")), "the next request did not reach the server");
      assertTrue(elapsed(started).compareTo(Duration.ofSeconds(15)) < 0,
          "the next request took " + elapsed(started) + "; stale connections were not evicted");
    } finally {
      ((AutoCloseable) dataSource).close();
    }
  }

  private static int selectOne(DataSource dataSource, String sql) throws SQLException {
    try (Connection connection = dataSource.getConnection();
         Statement statement = connection.createStatement();
         ResultSet row = statement.executeQuery(sql)) {
      row.next();
      return row.getInt(1);
    }
  }

  private interface Call {
    Object run() throws SQLException;
  }

  /** The call's result or its SQLException; the test fails if it is still running after {@link #HANG}. */
  private static Object within(Call call) throws InterruptedException {
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    Future<Object> pending = executor.submit(call::run);
    try {
      return pending.get(HANG.toSeconds(), TimeUnit.SECONDS);
    } catch (ExecutionException failed) {
      return failed.getCause();
    } catch (TimeoutException hung) {
      pending.cancel(true);
      return fail("the request hung for " + HANG + " on a server that stopped answering");
    } finally {
      executor.shutdownNow();
    }
  }

  private static Duration elapsed(long startedNanos) {
    return Duration.ofNanos(System.nanoTime() - startedNanos);
  }
}
