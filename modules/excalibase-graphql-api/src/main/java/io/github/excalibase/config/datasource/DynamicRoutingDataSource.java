package io.github.excalibase.config.datasource;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Routes to tenant-specific datasources when a ScopedValue tenant is set,
 * otherwise falls back to the default static datasource.
 */
public class DynamicRoutingDataSource extends AbstractRoutingDataSource {

    public static final String DEFAULT_KEY = "default";

    private final DynamicDataSourceManager dataSourceManager;
    private final boolean hasDefaultTarget;

    public DynamicRoutingDataSource(DynamicDataSourceManager dataSourceManager, boolean hasDefaultTarget) {
        this.dataSourceManager = dataSourceManager;
        this.hasDefaultTarget = hasDefaultTarget;
    }

    @Override
    protected Object determineCurrentLookupKey() {
        String tenantId = TenantContext.getTenantId();
        return tenantId != null ? tenantId : DEFAULT_KEY;
    }

    @Override
    protected DataSource determineTargetDataSource() {
        String tenantId = TenantContext.getTenantId();
        String orgSlug = TenantContext.getOrgSlug();
        if (tenantId != null && orgSlug != null && dataSourceManager != null) {
            return dataSourceManager.getDataSource(orgSlug, tenantId);
        }
        return super.determineTargetDataSource();
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (!hasDefaultTarget && TenantContext.getTenantId() == null) {
            throw new SQLException(
                "No default datasource configured and no tenant context set. "
                + "Provide spring.datasource.url or use a JWT with projectId claim.");
        }
        return super.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        if (!hasDefaultTarget && TenantContext.getTenantId() == null) {
            throw new SQLException(
                "No default datasource configured and no tenant context set.");
        }
        return super.getConnection(username, password);
    }
}
