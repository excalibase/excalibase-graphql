package io.github.excalibase.rls;

import io.github.excalibase.config.JwtSecurityConfig;
import io.github.excalibase.permissions.NoPermissionSource;
import io.github.excalibase.permissions.PermissionProvider;
import io.github.excalibase.permissions.ProvisioningPermissionProvider;
import io.github.excalibase.schema.GraphqlSchemaManager;
import io.github.excalibase.security.TokenFileSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** The permission cache is chosen by the same property as the policy source and dropped by the same signal. */
class PermissionEvictionWiringTest {

    private static final TokenFileSource TOKEN = new TokenFileSource(null, "svc-graphql-token");

    @Test
    void permissionProvider_readsFromProvisioningWhenThePolicyUrlIsSet() {
        PermissionProvider provider = new JwtSecurityConfig()
                .permissionProvider("http://provisioning/api", TOKEN, 30_000);

        assertThat(provider).isInstanceOf(ProvisioningPermissionProvider.class);
    }

    @Test
    void permissionProvider_withoutAPolicyUrl_hasNoSource() {
        assertThat(new JwtSecurityConfig().permissionProvider("", TOKEN, 30_000))
                .isInstanceOf(NoPermissionSource.class);
        assertThat(new JwtSecurityConfig().permissionProvider(null, TOKEN, 30_000))
                .isInstanceOf(NoPermissionSource.class);
    }

    @Test
    void policyChange_evictsThePermissionsAlongsidePoliciesAndSchemas() {
        PolicyProvider policies = mock(PolicyProvider.class);
        PermissionProvider permissions = mock(PermissionProvider.class);
        GraphqlSchemaManager schemas = mock(GraphqlSchemaManager.class);

        PolicyChangeSubscriber subscriber = new JwtSecurityConfig().policyChangeSubscriber(
                policies, permissions, schemas, false, "nats://localhost:4222", "", "", "");
        subscriber.evictFor("policies.proj-abc.changed");

        verify(policies).evict("proj-abc");
        verify(permissions).evict("proj-abc");
        verify(schemas).evict("proj-abc");
    }
}
