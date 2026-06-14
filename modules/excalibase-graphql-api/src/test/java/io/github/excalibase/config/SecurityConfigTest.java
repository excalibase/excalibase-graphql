package io.github.excalibase.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the startup guard in {@link SecurityConfig}: a multi-tenant /
 * provisioning deployment must not run with JWT authentication disabled.
 */
class SecurityConfigTest {

    @Test
    @DisplayName("provisioning-url set + jwt-enabled=false → fails fast at startup")
    void multiTenantWithoutJwt_failsFast() {
        SecurityConfig config = new SecurityConfig(false, "http://provisioning:24005/api");

        assertThatThrownBy(config::validateMultiTenantRequiresJwt)
                .isInstanceOf(IllegalStateException.class)
                .satisfies(ex -> assertThat(ex.getMessage().toLowerCase())
                        .contains("jwt-enabled")
                        .contains("multi-tenant"));
    }

    @Test
    @DisplayName("provisioning-url set + jwt-enabled=true → starts cleanly")
    void multiTenantWithJwt_ok() {
        SecurityConfig config = new SecurityConfig(true, "http://provisioning:24005/api");
        assertThatCode(config::validateMultiTenantRequiresJwt).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("no provisioning-url + jwt-enabled=false → single-tenant stays permissive")
    void singleTenantWithoutJwt_ok() {
        SecurityConfig config = new SecurityConfig(false, "");
        assertThatCode(config::validateMultiTenantRequiresJwt).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("no provisioning-url + jwt-enabled=true → ok")
    void singleTenantWithJwt_ok() {
        SecurityConfig config = new SecurityConfig(true, null);
        assertThatCode(config::validateMultiTenantRequiresJwt).doesNotThrowAnyException();
    }
}
