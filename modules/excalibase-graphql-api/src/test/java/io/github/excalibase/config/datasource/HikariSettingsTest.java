package io.github.excalibase.config.datasource;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HikariSettingsTest {

    @Test
    @DisplayName("the pool is sized as the earlier build sized it unless told otherwise")
    void defaults() {
        HikariConfig config = new HikariConfig();

        new HikariSettings().applyTo(config);

        assertThat(config.getMaximumPoolSize()).isEqualTo(50);
        assertThat(config.getMinimumIdle()).isEqualTo(2);
        assertThat(config.getIdleTimeout()).isEqualTo(600_000);
        assertThat(config.getMaxLifetime()).isEqualTo(1_800_000);
    }

    @Test
    @DisplayName("configured sizes and timeouts override the defaults")
    void configured() {
        HikariSettings settings = new HikariSettings();
        settings.setMaximumPoolSize(5);
        settings.setMinimumIdle(1);
        settings.setIdleTimeout(300_000);
        settings.setMaxLifetime(900_000);
        HikariConfig config = new HikariConfig();

        settings.applyTo(config);

        assertThat(config.getMaximumPoolSize()).isEqualTo(5);
        assertThat(config.getMinimumIdle()).isEqualTo(1);
        assertThat(config.getIdleTimeout()).isEqualTo(300_000);
        assertThat(config.getMaxLifetime()).isEqualTo(900_000);
    }
}
