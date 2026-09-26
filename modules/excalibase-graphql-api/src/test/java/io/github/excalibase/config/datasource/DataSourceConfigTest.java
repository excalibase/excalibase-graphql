package io.github.excalibase.config.datasource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** There is no built-in database: single-database mode names one, multi-tenant mode has none. */
class DataSourceConfigTest {

    private static final String URL = "jdbc:postgresql://db:5432/app";

    @Test
    @DisplayName("single-database mode with a URL and credentials registers that database")
    void singleDatabase_withSettings_registersIt() {
        assertThat(DataSourceConfig.registersDefault(URL, "app_user", "secret", false)).isTrue();
    }

    @Test
    @DisplayName("single-database mode without a URL refuses to start")
    void singleDatabase_withoutUrl_refuses() {
        assertThatThrownBy(() -> DataSourceConfig.registersDefault("", "app_user", "secret", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.url");
        assertThatThrownBy(() -> DataSourceConfig.registersDefault(null, "app_user", "secret", false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("single-database mode without credentials refuses to start")
    void singleDatabase_withoutCredentials_refuses() {
        assertThatThrownBy(() -> DataSourceConfig.registersDefault(URL, "", "secret", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.username");
        assertThatThrownBy(() -> DataSourceConfig.registersDefault(URL, "app_user", null, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.password");
    }

    @Test
    @DisplayName("multi-tenant mode registers no default database")
    void multiTenant_registersNoDefault() {
        assertThat(DataSourceConfig.registersDefault("", null, null, true)).isFalse();
        assertThat(DataSourceConfig.registersDefault(null, null, null, true)).isFalse();
    }

    @Test
    @DisplayName("multi-tenant mode with a default URL configured refuses to start rather than ignore it")
    void multiTenant_withUrl_refuses() {
        assertThatThrownBy(() -> DataSourceConfig.registersDefault(URL, "app_user", "secret", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("multi-tenant");
    }
}
