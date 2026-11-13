package io.github.excalibase.config.datasource;

import com.zaxxer.hikari.HikariDataSource;
import io.github.excalibase.cache.TTLCache;
import io.github.excalibase.service.VaultCredentialService;
import io.github.excalibase.service.VaultCredentials;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class DynamicDataSourceManager {

  private static final Logger log = LoggerFactory.getLogger(DynamicDataSourceManager.class);
  // Pools are rebuilt from a fresh vault record at this age at the latest, which is
  // how renewed client certificates (and a rotated CA) reach new connections.
  private static final Duration MAX_POOL_AGE = Duration.ofHours(1);
  private final VaultCredentialService vaultService;
  private final Function<VaultCredentials, DataSource> dataSourceFactory;
  // An aged-out pool is closed this long after leaving the cache, so the queries running on it finish:
  // longer than the read timeout, which bounds each of them.
  private static final Duration RETIRE_GRACE = Duration.ofSeconds(90);
  private final TTLCache<String, TenantDatabase> cache;
  private final Set<HikariDataSource> retiring = ConcurrentHashMap.newKeySet();
  private final ScheduledExecutorService retirer = Executors.newSingleThreadScheduledExecutor(task -> {
    Thread thread = new Thread(task, "tenant-pool-retirer");
    thread.setDaemon(true);
    return thread;
  });
  private final Duration retireGrace;

  /** Production constructor — pools built by {@code dataSourceFactory}, rebuilt at most hourly. */
  public DynamicDataSourceManager(VaultCredentialService vaultService, TenantDataSourceFactory dataSourceFactory,
                                  int ttlMinutes) {
    this(vaultService, dataSourceFactory, maxPoolAge(ttlMinutes));
  }

  /** Test constructor — injectable factory for mock datasources. */
  public DynamicDataSourceManager(VaultCredentialService vaultService,
                                  Function<VaultCredentials, DataSource> dataSourceFactory) {
    this(vaultService, dataSourceFactory, maxPoolAge(30));
  }

  DynamicDataSourceManager(VaultCredentialService vaultService,
                           Function<VaultCredentials, DataSource> dataSourceFactory,
                           Duration poolAge) {
    this(vaultService, dataSourceFactory, poolAge, RETIRE_GRACE);
  }

  DynamicDataSourceManager(VaultCredentialService vaultService,
                           Function<VaultCredentials, DataSource> dataSourceFactory,
                           Duration poolAge, Duration retireGrace) {
    this.vaultService = vaultService;
    this.dataSourceFactory = dataSourceFactory;
    this.retireGrace = retireGrace;
    this.cache = new TTLCache<>(poolAge, this::retire);
  }

  /** A pool that left the cache stops being handed out; it is closed after the grace period, not under a running query. */
  private void retire(TenantDatabase database) {
    if (database.dataSource() instanceof HikariDataSource hikari && retiring.add(hikari)) {
      retirer.schedule(() -> close(hikari, "closed_expired_datasource"), retireGrace.toMillis(), TimeUnit.MILLISECONDS);
    }
  }

  private void close(HikariDataSource hikari, String event) {
    if (retiring.remove(hikari) || !hikari.isClosed()) {
      hikari.close();
      log.info("{} pool={}", event, hikari.getPoolName());
    }
  }

  /**
   * Cache key is the opaque {@code projectId} — globally unique, so no composition with
   * {@code orgSlug} needed. {@code orgSlug} is still required to address the vault path
   * {@code projects/{orgSlug}/{projectId}/credentials/...}.
   */
  public DataSource getDataSource(String orgSlug, String projectId) {
    return getDatabase(orgSlug, projectId).dataSource();
  }

  public TenantDatabase getDatabase(String orgSlug, String projectId) {
    return cache.computeIfAbsent(projectId, key -> {
      VaultCredentials creds = vaultService.fetchCredentials(orgSlug, projectId);
      log.info("created_datasource tenant={} engine={} url={}", projectId, creds.engine().type(), creds.jdbcUrl());
      return new TenantDatabase(dataSourceFactory.apply(creds), creds.engine());
    });
  }

  public void evict(String cacheKey) {
    TenantDatabase removed = cache.remove(cacheKey);
    if (removed != null && removed.dataSource() instanceof HikariDataSource hikari) {
      close(hikari, "evicted_datasource");
    }
  }

  static Duration maxPoolAge(int ttlMinutes) {
    Duration configured = Duration.ofMinutes(ttlMinutes);
    return configured.compareTo(MAX_POOL_AGE) > 0 ? MAX_POOL_AGE : configured;
  }

  public boolean isCached(String cacheKey) {
    return cache.get(cacheKey) != null;
  }

  @PreDestroy
  public void destroy() {
    cache.clear();
    cache.shutdown();
    retirer.shutdownNow();
    List.copyOf(retiring).forEach(pool -> close(pool, "closed_retiring_datasource"));
    if (dataSourceFactory instanceof TenantDataSourceFactory tenantFactory) {
      tenantFactory.close();
    }
    log.info("tenant_datasource_manager_destroyed");
  }
}
