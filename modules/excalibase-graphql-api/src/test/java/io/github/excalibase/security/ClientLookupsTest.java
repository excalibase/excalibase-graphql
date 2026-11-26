package io.github.excalibase.security;

import io.github.excalibase.config.datasource.DynamicDataSourceManager;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** One client asking for made-up projects cannot use up the lookups every other client needs (EXC-831). */
class ClientLookupsTest {

    private static final long NEVER_REFILLS = 0L;

    @Test
    void aClientOverItsRate_isRefused_whileAnotherClientIsNot() {
        ClientLookups lookups = new ClientLookups(1, 100, () -> NEVER_REFILLS);

        assertThat(lookups.tryAcquire("1.1.1.1")).isTrue();
        assertThat(lookups.tryAcquire("1.1.1.1")).isTrue();
        assertThat(lookups.tryAcquire("1.1.1.1")).isFalse();
        assertThat(lookups.tryAcquire("2.2.2.2")).isTrue();
    }

    @Test
    void theClientsHeldAreBounded() {
        ClientLookups lookups = new ClientLookups(1, 2, () -> NEVER_REFILLS);

        lookups.tryAcquire("a");
        lookups.tryAcquire("b");
        lookups.tryAcquire("c");

        assertThat(lookups.clientsHeld()).isEqualTo(2);
    }

    @Test
    void tenantsThrottleByClient_beforeTheSharedAllowance() {
        DynamicDataSourceManager manager = mock(DynamicDataSourceManager.class);
        UnknownProjectCache unknown = new UnknownProjectCache(100, Duration.ofSeconds(30), System::currentTimeMillis);
        KnownProjects projects = KnownProjects.tenants(manager, unknown, LookupLimiter.perSecond(50),
                new ClientLookups(1, 100, () -> NEVER_REFILLS));

        projects.require("p-1", "1.1.1.1");
        projects.require("p-2", "1.1.1.1");

        assertThatThrownBy(() -> projects.require("p-3", "1.1.1.1")).isInstanceOf(ProjectLookupThrottledException.class);
        assertThatCode(() -> projects.require("p-4", "2.2.2.2")).doesNotThrowAnyException();
    }
}
