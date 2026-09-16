package io.github.excalibase.rls;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exposure layer must only activate behind a real grant source. A provider
 * with nothing configured returns empty for every project, which is permissive
 * for policies but would deny every table if read as grants.
 */
class GrantSourceActivationTest {

    @Test
    @DisplayName("servesGrants is false for a provider with no configured source")
    void servesGrants_inMemoryProvider_isFalse() {
        assertThat(new InMemoryPolicyProvider().servesGrants()).isFalse();
    }

    @Test
    @DisplayName("servesGrants is true for the provisioning-backed provider")
    void servesGrants_provisioningProvider_isTrue() {
        PolicyProvider provider = new ProvisioningPolicyProvider(
                "http://localhost:1/api", "token", 1000L);

        assertThat(provider.servesGrants()).isTrue();
    }

    @Test
    @DisplayName("an unconfigured provider still reports no grants, so the default stays deny")
    void grantsFor_inMemoryProvider_staysEmpty() {
        List<TableGrant> grants = new InMemoryPolicyProvider().grantsFor("any-project");

        assertThat(grants).isEmpty();
    }
}
