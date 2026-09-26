package io.github.excalibase.security;

import io.github.excalibase.config.datasource.DynamicDataSourceManager;
import io.github.excalibase.service.VaultCredentialException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A 404 from the vault is remembered briefly, so a stream of requests for a
 * missing project costs one vault call per TTL instead of one per request.
 */
class UnknownProjectCacheTest {

    private static final Duration TTL = Duration.ofSeconds(30);

    private final long[] now = {1_000L};
    private final DynamicDataSourceManager manager = mock(DynamicDataSourceManager.class);

    private UnknownProjectCache cache(int maxEntries) {
        return new UnknownProjectCache(maxEntries, TTL, () -> now[0]);
    }

    @Test
    @DisplayName("a repeated unknown project costs one vault call within the TTL")
    void repeatedUnknownProject_oneVaultCall() {
        when(manager.getDataSource(null, "proj-gone")).thenThrow(new UnknownProjectException("proj-gone"));
        KnownProjects projects = KnownProjects.tenants(manager, cache(100));

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> projects.require("proj-gone")).isInstanceOf(UnknownProjectException.class);
        }

        verify(manager, times(1)).getDataSource(null, "proj-gone");
    }

    @Test
    @DisplayName("many distinct unknown ids never grow the cache past its bound")
    void distinctUnknownIds_stayWithinTheBound() {
        UnknownProjectCache cache = cache(50);

        for (int i = 0; i < 10_000; i++) {
            cache.recordMissing("proj-random-" + i);
        }

        assertThat(cache.size()).isEqualTo(50);
        assertThat(cache.isKnownMissing("proj-random-9999")).isTrue();
        assertThat(cache.isKnownMissing("proj-random-0")).isFalse();
    }

    @Test
    @DisplayName("a project created after a 404 becomes reachable once the TTL has passed")
    void projectCreatedAfterA404_reachableAfterTtl() {
        DataSource created = mock(DataSource.class);
        when(manager.getDataSource(null, "proj-new"))
                .thenThrow(new UnknownProjectException("proj-new"))
                .thenReturn(created);
        KnownProjects projects = KnownProjects.tenants(manager, cache(100));

        assertThatThrownBy(() -> projects.require("proj-new")).isInstanceOf(UnknownProjectException.class);
        now[0] += TTL.toMillis() - 1;
        assertThatThrownBy(() -> projects.require("proj-new")).isInstanceOf(UnknownProjectException.class);
        now[0] += 2;
        assertThatCode(() -> projects.require("proj-new")).doesNotThrowAnyException();

        verify(manager, times(2)).getDataSource(null, "proj-new");
    }

    @Test
    @DisplayName("a vault error is not cached: the next request asks the vault again")
    void vaultError_isNotCached() {
        when(manager.getDataSource(null, "proj-a"))
                .thenThrow(new VaultCredentialException("vault unreachable"))
                .thenReturn(mock(DataSource.class));
        UnknownProjectCache cache = cache(100);
        KnownProjects projects = KnownProjects.tenants(manager, cache);

        assertThatThrownBy(() -> projects.require("proj-a")).isInstanceOf(VaultCredentialException.class);
        assertThat(cache.size()).isZero();
        assertThatCode(() -> projects.require("proj-a")).doesNotThrowAnyException();

        verify(manager, times(2)).getDataSource(null, "proj-a");
    }

    @Test
    @DisplayName("the bound and TTL must be positive")
    void invalidLimits_refused() {
        assertThatThrownBy(() -> new UnknownProjectCache(0, TTL, () -> 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UnknownProjectCache(10, Duration.ZERO, () -> 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
