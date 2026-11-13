package io.github.excalibase.security;

import io.github.excalibase.config.datasource.DynamicDataSourceManager;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** EXC-722: made-up project ids cost no more than a bounded number of provisioning calls. */
class LookupLimiterTest {

    private static UnknownProjectCache unknown() {
        return new UnknownProjectCache(100, Duration.ofSeconds(30), System::currentTimeMillis);
    }

    @Test
    void theBucketRefillsOverTime() {
        long[] now = {0};
        LookupLimiter limiter = new LookupLimiter(1, () -> now[0]);

        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
        now[0] += TimeUnit.SECONDS.toNanos(1);
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    void anIdThatCannotBeAProjectIsUnknownWithoutALookup() {
        DynamicDataSourceManager manager = mock(DynamicDataSourceManager.class);
        KnownProjects projects = KnownProjects.tenants(manager, unknown());

        for (String malformed : new String[] {"a b", "x'y", "../etc", "a".repeat(65), "a;b", "é"}) {
            assertThatThrownBy(() -> projects.require(malformed)).isInstanceOf(UnknownProjectException.class);
        }
        verify(manager, never()).getDataSource(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void unresolvedProjectsBeyondTheRateAreThrottledAndCostNoLookup() {
        DynamicDataSourceManager manager = mock(DynamicDataSourceManager.class);
        KnownProjects projects = KnownProjects.tenants(manager, unknown(), new LookupLimiter(1, () -> 0L));

        projects.require("proj-1");
        projects.require("proj-2");
        assertThatThrownBy(() -> projects.require("proj-3")).isInstanceOf(ProjectLookupThrottledException.class);
        verify(manager, never()).getDataSource(null, "proj-3");
    }

    @Test
    void aResolvedProjectIsNeverThrottled() {
        DynamicDataSourceManager manager = mock(DynamicDataSourceManager.class);
        when(manager.isCached("proj-hot")).thenReturn(true);
        KnownProjects projects = KnownProjects.tenants(manager, unknown(), new LookupLimiter(1, () -> 0L));
        projects.require("proj-1");
        projects.require("proj-2");

        projects.require("proj-hot");
        verify(manager).getDataSource(null, "proj-hot");
    }
}
