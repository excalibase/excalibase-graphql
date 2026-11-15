package io.github.excalibase.config;

import io.github.excalibase.config.ws.WsRuntime;
import io.github.excalibase.permissions.NoPermissionSource;
import io.github.excalibase.permissions.PermissionProvider;
import io.github.excalibase.permissions.ProvisioningPermissionProvider;
import io.github.excalibase.rls.PolicyChangeSubscriber;
import io.github.excalibase.schema.GraphqlSchemaManager;
import io.github.excalibase.security.TokenFileSource;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** The permission source is chosen by the policy URL, and its cache is dropped by the same signal as the schemas. */
class PermissionEvictionWiringTest {

    private static final TokenFileSource TOKEN = new TokenFileSource(null, "svc-graphql-token");

    @Test
    void permissionProvider_readsFromProvisioningWhenThePolicyUrlIsSet() {
        PermissionProvider provider = new AppConfig()
                .permissionProvider("http://provisioning/api", TOKEN, 30_000, 300_000, 2_000, null);

        assertThat(provider).isInstanceOf(ProvisioningPermissionProvider.class);
    }

    @Test
    void permissionProvider_withoutAPolicyUrl_hasNoSource() {
        assertThat(new AppConfig().permissionProvider("", TOKEN, 30_000, 300_000, 2_000, null))
                .isInstanceOf(NoPermissionSource.class);
        assertThat(new AppConfig().permissionProvider(null, TOKEN, 30_000, 300_000, 2_000, null))
                .isInstanceOf(NoPermissionSource.class);
    }

    @Test
    void permissionProvider_refusesAWindowOrIntervalThatIsNotPositive_withOrWithoutASource() {
        AppConfig config = new AppConfig();

        assertThatThrownBy(() -> config.permissionProvider("http://provisioning/api", TOKEN, 30_000, 0, 2_000, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.security.permissions.max-stale-ms");
        assertThatThrownBy(() -> config.permissionProvider("", TOKEN, 30_000, -5, 2_000, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.permissionProvider("", TOKEN, 30_000, 300_000, 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.security.permissions.retry-interval-ms");
    }

    @Test
    void policyChange_evictsThePermissionsAndTheSchemas() {
        PermissionProvider permissions = mock(PermissionProvider.class);
        GraphqlSchemaManager schemas = mock(GraphqlSchemaManager.class);
        WsRuntime webSockets = mock(WsRuntime.class);

        PolicyChangeSubscriber subscriber = new AppConfig().policyChangeSubscriber(
                permissions, schemas, webSockets, false, "nats://localhost:4222", "", "", "");
        evictFor(subscriber, "policies.proj-abc.changed");

        verify(permissions).evict("proj-abc");
        verify(schemas).evict("proj-abc");
        verify(webSockets).hasSockets("proj-abc");
    }

    private static void evictFor(PolicyChangeSubscriber subscriber, String subject) {
        try {
            Method evictFor = PolicyChangeSubscriber.class.getDeclaredMethod("evictFor", String.class);
            evictFor.setAccessible(true);
            evictFor.invoke(subscriber, subject);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
