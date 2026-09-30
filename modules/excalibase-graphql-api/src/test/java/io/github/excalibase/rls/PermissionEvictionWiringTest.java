package io.github.excalibase.rls;

import io.github.excalibase.config.JwtSecurityConfig;
import io.github.excalibase.permissions.NoPermissionSource;
import io.github.excalibase.permissions.PermissionProvider;
import io.github.excalibase.permissions.ProvisioningPermissionProvider;
import io.github.excalibase.schema.GraphqlSchemaManager;
import io.github.excalibase.security.TokenFileSource;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** The permission source is chosen by the policy URL, and its cache is dropped by the same signal as the schemas. */
class PermissionEvictionWiringTest {

    private static final TokenFileSource TOKEN = new TokenFileSource(null, "svc-graphql-token");
    private static final ObjectProvider<MeterRegistry> METERS = new StaticListableBeanFactory()
            .getBeanProvider(MeterRegistry.class);

    @Test
    void permissionProvider_readsFromProvisioningWhenThePolicyUrlIsSet() {
        PermissionProvider provider = new JwtSecurityConfig()
                .permissionProvider("http://provisioning/api", TOKEN, 30_000, 300_000, 2_000, METERS);

        assertThat(provider).isInstanceOf(ProvisioningPermissionProvider.class);
    }

    @Test
    void permissionProvider_withoutAPolicyUrl_hasNoSource() {
        assertThat(new JwtSecurityConfig().permissionProvider("", TOKEN, 30_000, 300_000, 2_000, METERS))
                .isInstanceOf(NoPermissionSource.class);
        assertThat(new JwtSecurityConfig().permissionProvider(null, TOKEN, 30_000, 300_000, 2_000, METERS))
                .isInstanceOf(NoPermissionSource.class);
    }

    @Test
    void permissionProvider_refusesAWindowOrIntervalThatIsNotPositive_withOrWithoutASource() {
        JwtSecurityConfig config = new JwtSecurityConfig();

        assertThatThrownBy(() -> config.permissionProvider("http://provisioning/api", TOKEN, 30_000, 0, 2_000, METERS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.security.permissions.max-stale-ms");
        assertThatThrownBy(() -> config.permissionProvider("", TOKEN, 30_000, -5, 2_000, METERS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.permissionProvider("", TOKEN, 30_000, 300_000, 0, METERS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.security.permissions.retry-interval-ms");
    }

    @Test
    void policyChange_evictsThePermissionsAlongsideTheSchemas() {
        PermissionProvider permissions = mock(PermissionProvider.class);
        GraphqlSchemaManager schemas = mock(GraphqlSchemaManager.class);

        PolicyChangeSubscriber subscriber = new JwtSecurityConfig().policyChangeSubscriber(
                permissions, schemas, false, "nats://localhost:4222", "", "", "");
        subscriber.evictFor("policies.proj-abc.changed");

        verify(permissions).evict("proj-abc");
        verify(schemas).evict("proj-abc");
    }
}
