package io.github.excalibase.observability.actuator;

import io.github.excalibase.config.datasource.DynamicRoutingDataSource;
import io.github.excalibase.document.DocumentEngines;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whether the configured database answers. Multi-tenant mode has no configured database (every project has its
 * own), so nothing is checked there; {@code management.health.db.enabled=false} switches the check off.
 */
@Singleton
public class DatabaseHealth {

    private static final Logger log = LoggerFactory.getLogger(DatabaseHealth.class);
    private static final int VALIDATION_SECONDS = 2;

    private final Optional<DataSource> database;
    private final DocumentEngines documents;

    @Inject
    DatabaseHealth(DataSource dataSource, @Value("${management.health.db.enabled:true}") boolean enabled,
                   @Nullable DocumentEngines documents) {
        this.database = enabled && dataSource instanceof DynamicRoutingDataSource routing
                ? routing.configured() : Optional.empty();
        this.documents = enabled ? documents : null;
    }

    public HealthStatus status() {
        if (documents != null) {
            return documents.healthy() ? HealthStatus.UP : HealthStatus.DOWN;
        }
        return database.map(DatabaseHealth::check).orElse(HealthStatus.UP);
    }

    private static HealthStatus check(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(VALIDATION_SECONDS) ? HealthStatus.UP : HealthStatus.DOWN;
        } catch (SQLException unreachable) {
            log.warn("health: the database does not answer: {}", unreachable.getMessage());
            return HealthStatus.DOWN;
        }
    }
}
