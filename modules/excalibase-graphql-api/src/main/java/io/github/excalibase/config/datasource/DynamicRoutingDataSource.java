package io.github.excalibase.config.datasource;

import io.micronaut.core.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.sql.Connection;
import java.time.Duration;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;

/**
 * Routes to the calling project's own database when a tenant is set, otherwise to the one configured
 * database. Multi-tenant mode has no configured database, so a connection that names no project is refused.
 */
public class DynamicRoutingDataSource extends AbstractDataSource {

    private static final Logger log = LoggerFactory.getLogger(DynamicRoutingDataSource.class);
    private static final Duration CLOSE_WAIT = Duration.ofMillis(500);

    private final DynamicDataSourceManager dataSourceManager;
    private final DataSource defaultTarget;

    public DynamicRoutingDataSource(@Nullable DynamicDataSourceManager dataSourceManager,
                                    @Nullable DataSource defaultTarget) {
        this.dataSourceManager = dataSourceManager;
        this.defaultTarget = defaultTarget;
    }

    /** The one configured database, absent in multi-tenant mode where every project has its own. */
    public Optional<DataSource> configured() {
        return Optional.ofNullable(defaultTarget);
    }

    /**
     * Closes the configured database's pool; the projects' pools are the manager's to close. A pool whose database
     * is unreachable takes seconds to close, so the wait is bounded and the close finishes in the background.
     */
    public void close() {
        if (!(defaultTarget instanceof AutoCloseable pool)) {
            return;
        }
        Thread closer = new Thread(() -> closeQuietly(pool), "configured-pool-close");
        closer.setDaemon(true);
        closer.start();
        try {
            closer.join(CLOSE_WAIT.toMillis());
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(AutoCloseable pool) {
        try {
            pool.close();
        } catch (Exception e) {
            log.warn("configured_pool_close_failed: {}", e.getMessage());
        }
    }

    private DataSource target() throws SQLException {
        String tenantId = TenantContext.getTenantId();
        if (tenantId != null && dataSourceManager != null) {
            return dataSourceManager.getDataSource(TenantContext.getOrgSlug(), tenantId);
        }
        if (defaultTarget == null) {
            throw new SQLException("No default datasource configured and no tenant context set. "
                    + "Provide spring.datasource.url or use a JWT with projectId claim.");
        }
        return defaultTarget;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return target().getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return target().getConnection(username, password);
    }
}
