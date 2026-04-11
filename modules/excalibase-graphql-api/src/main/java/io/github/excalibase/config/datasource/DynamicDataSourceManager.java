package io.github.excalibase.config.datasource;

import com.zaxxer.hikari.HikariConfig;
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
  private final VaultCredentialService vaultService;
  private final Function<VaultCredentials, DataSource> dataSourceFactory;
  private final TTLCache<String, DataSource> cache;

  /** Production constructor — creates real HikariDataSource. */
  public DynamicDataSourceManager(VaultCredentialService vaultService, int ttlMinutes, int poolSize) {
    this(vaultService, creds -> createHikariDataSource(creds, poolSize), ttlMinutes);
  }

  /** Test constructor — injectable factory for mock datasources. */
  public DynamicDataSourceManager(VaultCredentialService vaultService,
                                  Function<VaultCredentials, DataSource> dataSourceFactory) {
    this(vaultService, dataSourceFactory, 30);
  }

  private DynamicDataSourceManager(VaultCredentialService vaultService,
                                   Function<VaultCredentials, DataSource> dataSourceFactory,
                                   int ttlMinutes) {
    this.vaultService = vaultService;
    this.dataSourceFactory = dataSourceFactory;
    this.cache = new TTLCache<>(Duration.ofMinutes(ttlMinutes), ds -> {
      if (ds instanceof HikariDataSource hikari) {
        hikari.close();
        log.info("closed_expired_datasource pool={}", hikari.getPoolName());
      }
    });
  }

  public DataSource getDataSource(String orgSlug, String projectName) {
    String cacheKey = orgSlug + "/" + projectName;
    return cache.computeIfAbsent(cacheKey, key -> {
      VaultCredentials creds = vaultService.fetchCredentials(orgSlug, projectName);
      log.info("created_datasource tenant={}/{} url={}", orgSlug, projectName, creds.jdbcUrl());
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

  public boolean isCached(String cacheKey) {
    return cache.get(cacheKey) != null;
  }

  @PreDestroy
  public void destroy() {
    cache.clear();
    cache.shutdown();
    log.info("tenant_datasource_manager_destroyed");
  }

  private static DataSource createHikariDataSource(VaultCredentials creds, int poolSize) {
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
    return new HikariDataSource(config);
  }
}
