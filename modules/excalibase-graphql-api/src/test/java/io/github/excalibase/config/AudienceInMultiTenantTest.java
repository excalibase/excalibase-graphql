package io.github.excalibase.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** EXC-727: the audience check cannot be off when many projects are served; CORS reads the name platform-aio sets. */
class AudienceInMultiTenantTest {

    @Test
    @DisplayName("multi-tenant mode refuses require-aud=false")
    void multiTenantRefusesAudienceOff() {
        assertThatThrownBy(() -> AppConfig.requireAudienceWhenMultiTenant("http://provisioning/api", false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("require-aud");
    }

    @Test
    @DisplayName("audience off stays possible for a single database, and on is always fine")
    void otherCombinationsStart() {
        assertThatCode(() -> AppConfig.requireAudienceWhenMultiTenant("", false)).doesNotThrowAnyException();
        assertThatCode(() -> AppConfig.requireAudienceWhenMultiTenant(null, false)).doesNotThrowAnyException();
        assertThatCode(() -> AppConfig.requireAudienceWhenMultiTenant("http://provisioning/api", true))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("CORS_ORIGINS sets the platform origins when APP_CORS_ALLOWED_ORIGINS is absent")
    void corsOriginsEnvName() {
        assertThat(AppConfig.platformOrigins("", "https://studio.example.test")).isEqualTo("https://studio.example.test");
        assertThat(AppConfig.platformOrigins(null, "https://studio.example.test")).isEqualTo("https://studio.example.test");
    }

    @Test
    @DisplayName("APP_CORS_ALLOWED_ORIGINS wins over CORS_ORIGINS, and neither set means any origin as before")
    void appNameWinsAndDefaultIsAny() {
        assertThat(AppConfig.platformOrigins("https://b.example.test", "https://a.example.test"))
                .isEqualTo("https://b.example.test");
        assertThat(AppConfig.platformOrigins("", "")).isEqualTo("*");
    }
}
