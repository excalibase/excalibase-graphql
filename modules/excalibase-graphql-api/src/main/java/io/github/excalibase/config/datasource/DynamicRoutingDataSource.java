package io.github.excalibase.config.datasource;

import io.micronaut.core.annotation.Nullable;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;

/**
 * Routes to the calling project's own database when a tenant is set, otherwise to the one configured
 * database. Multi-tenant mode has no configured database, so a connection that names no project is refused.
 */
public class DynamicRoutingDataSource extends AbstractDataSource {

    private final DynamicDataSourceManager dataSourceManager;
    private final DataSource defaultTarget;

    public DynamicRoutingDataSource(@Nullable DynamicDataSourceManager dataSourceManager,
                                    @Nullable DataSource defaultTarget) {
        this.dataSourceManager = dataSourceManager;
        this.defaultTarget = defaultTarget;
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
