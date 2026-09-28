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
import java.util.function.Function;

public class DynamicDataSourceManager {

  private static final Logger log = LoggerFactory.getLogger(DynamicDataSourceManager.class);
  // Pools are rebuilt from a fresh vault record at this age at the latest, which is
  // how renewed client certificates (and a rotated CA) reach new connections.
  private static final Duration MAX_POOL_AGE = Duration.ofHours(1);
  private final VaultCredentialService vaultService;
  private final Function<VaultCredentials, DataSource> dataSourceFactory;
  private final TTLCache<String, DataSource> cache;

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
    this.vaultService = vaultService;
    this.dataSourceFactory = dataSourceFactory;
    this.cache = new TTLCache<>(poolAge, ds -> {
      if (ds instanceof HikariDataSource hikari) {
        hikari.close();
        log.info("closed_expired_datasource pool={}", hikari.getPoolName());
      }
    });
  }

  /**
   * Cache key is the opaque {@code projectId} — globally unique, so no composition with
   * {@code orgSlug} needed. {@code orgSlug} is still required to address the vault path
   * {@code projects/{orgSlug}/{projectId}/credentials/...}.
   */
  public DataSource getDataSource(String orgSlug, String projectId) {
    return cache.computeIfAbsent(projectId, key -> {
      VaultCredentials creds = vaultService.fetchCredentials(orgSlug, projectId);
      log.info("created_datasource tenant={} url={}", projectId, creds.jdbcUrl());
      return dataSourceFactory.apply(creds);
    });
  }

  public void evict(String cacheKey) {
    DataSource removed = cache.remove(cacheKey);
    if (removed instanceof HikariDataSource hikari) {
      hikari.close();
      log.info("evicted_datasource key={}", cacheKey);
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
    if (dataSourceFactory instanceof TenantDataSourceFactory tenantFactory) {
      tenantFactory.close();
    }
    log.info("tenant_datasource_manager_destroyed");
  }
}
