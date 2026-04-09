package io.github.excalibase.config.datasource;

import io.github.excalibase.service.VaultCredentialService;
import io.github.excalibase.service.VaultCredentials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DynamicDataSourceManagerTest {

  @Mock
  private VaultCredentialService vaultService;

  @Mock
  private DataSource mockDataSource;

  @Mock
  private DataSource mockDataSourceB;

  private DynamicDataSourceManager manager;

  private static final VaultCredentials CREDS = new VaultCredentials(
      "localhost", "5432", "app_db", "excalibase_app", "secret"
  );

  @BeforeEach
  void setUp() {
    // Use mock factory that returns mock datasources instead of real HikariDataSource
    manager = new DynamicDataSourceManager(vaultService, creds -> mockDataSource);
  }

  @Test
  @DisplayName("getDataSource fetches credentials from vault on cache miss")
  void getDataSource_cacheMiss_fetchesFromVault() {
    when(vaultService.fetchCredentials("duc-corp", "app-a")).thenReturn(CREDS);

    DataSource ds = manager.getDataSource("duc-corp", "app-a");

    assertNotNull(ds);
    verify(vaultService, times(1)).fetchCredentials("duc-corp", "app-a");
  }

  @Test
  @DisplayName("getDataSource returns cached datasource on cache hit")
  void getDataSource_cacheHit_doesNotFetchAgain() {
    when(vaultService.fetchCredentials("duc-corp", "app-a")).thenReturn(CREDS);

    DataSource first = manager.getDataSource("duc-corp", "app-a");
    DataSource second = manager.getDataSource("duc-corp", "app-a");

    assertSame(first, second);
    verify(vaultService, times(1)).fetchCredentials("duc-corp", "app-a");
  }

  @Test
  @DisplayName("getDataSource creates separate datasources for different tenants")
  void getDataSource_differentTenants_separateDataSources() {
    var credsB = new VaultCredentials("localhost", "5433", "app_b", "excalibase_app", "other");
    // Use a factory that returns different datasources based on credentials
    var managerMulti = new DynamicDataSourceManager(vaultService, creds ->
        creds.database().equals("app_db") ? mockDataSource : mockDataSourceB);

    when(vaultService.fetchCredentials("duc-corp", "app-a")).thenReturn(CREDS);
    when(vaultService.fetchCredentials("duc-corp", "app-b")).thenReturn(credsB);

    DataSource dsA = managerMulti.getDataSource("duc-corp", "app-a");
    DataSource dsB = managerMulti.getDataSource("duc-corp", "app-b");

    assertNotSame(dsA, dsB);
    verify(vaultService).fetchCredentials("duc-corp", "app-a");
    verify(vaultService).fetchCredentials("duc-corp", "app-b");
  }

  @Test
  @DisplayName("evict removes cached datasource")
  void evict_removesCachedEntry() {
    when(vaultService.fetchCredentials("duc-corp", "app-a")).thenReturn(CREDS);

    manager.getDataSource("duc-corp", "app-a");
    manager.evict("duc-corp/app-a");
    manager.getDataSource("duc-corp", "app-a");

    verify(vaultService, times(2)).fetchCredentials("duc-corp", "app-a");
  }

  @Test
  @DisplayName("getDataSource uses projectId as cache key (orgSlug/projectName)")
  void getDataSource_usesCombinedCacheKey() {
    when(vaultService.fetchCredentials("org-x", "proj-y")).thenReturn(CREDS);

    manager.getDataSource("org-x", "proj-y");

    assertTrue(manager.isCached("org-x/proj-y"));
    assertFalse(manager.isCached("org-x/proj-z"));
  }
}
