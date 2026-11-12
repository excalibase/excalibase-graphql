package io.github.excalibase.config.datasource;

import com.zaxxer.hikari.HikariConfig;
import io.micronaut.context.annotation.ConfigurationProperties;

/** {@code spring.datasource.hikari.*}: the sizing and aging of the configured database's pool, under the names the charts set. */
@ConfigurationProperties("spring.datasource.hikari")
public class HikariSettings {

    private int maximumPoolSize = 50;
    private int minimumIdle = 2;
    private long idleTimeout = 600_000;
    private long maxLifetime = 1_800_000;

    public void setMaximumPoolSize(int maximumPoolSize) {
        this.maximumPoolSize = maximumPoolSize;
    }

    public void setMinimumIdle(int minimumIdle) {
        this.minimumIdle = minimumIdle;
    }

    public void setIdleTimeout(long idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    public void setMaxLifetime(long maxLifetime) {
        this.maxLifetime = maxLifetime;
    }

    public void applyTo(HikariConfig config) {
        config.setMaximumPoolSize(maximumPoolSize);
        config.setMinimumIdle(minimumIdle);
        config.setIdleTimeout(idleTimeout);
        config.setMaxLifetime(maxLifetime);
    }
}
