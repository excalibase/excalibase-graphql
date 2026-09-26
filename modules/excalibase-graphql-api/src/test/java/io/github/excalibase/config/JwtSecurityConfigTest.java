package io.github.excalibase.config;

import io.github.excalibase.security.TokenFileSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtSecurityConfigTest {

    @Test
    @DisplayName("with no property set authentication is on, so a missing verifier fails startup")
    void noProperty_authOnByDefault() {
        new ApplicationContextRunner()
                .withUserConfiguration(JwtSecurityConfig.class)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("app.security.auth.jwks-url"));
    }

    @Test
    @DisplayName("authentication on with neither JWKS nor HMAC refuses with a clear message")
    void noVerifier_refuses() {
        SecurityProperties props = new SecurityProperties(true,
                new SecurityProperties.Auth(null, " ", null, null, null, null), null);

        assertThatThrownBy(() -> new JwtSecurityConfig().jwtService(props, 30))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.security.auth.jwks-url")
                .hasMessageContaining("app.security.auth.hmac-secret")
                .hasMessageContaining("app.security.insecure-dev-mode");
    }

    @Test
    @DisplayName("tenant routing exists only when a provisioning URL is actually set, not for a blank one")
    void tenantRouting_onlyWithAProvisioningUrl() {
        TokenFileSource token = new TokenFileSource(null, "pat");
        SecurityProperties blank = new SecurityProperties(true, null,
                new SecurityProperties.MultiTenant("", null, null));
        SecurityProperties configured = new SecurityProperties(true, null,
                new SecurityProperties.MultiTenant("http://provisioning/api", null, null));

        assertThat(new JwtSecurityConfig().dynamicDataSourceManager(blank, token, 30, 5)).isNull();
        assertThat(new JwtSecurityConfig().dynamicDataSourceManager(configured, token, 30, 5)).isNotNull();
    }
}
