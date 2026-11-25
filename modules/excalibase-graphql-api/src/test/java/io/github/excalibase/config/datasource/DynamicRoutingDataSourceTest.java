package io.github.excalibase.config.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DynamicRoutingDataSourceTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static DataSource answering(Connection connection) throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        return dataSource;
    }

    @Test
    @DisplayName("a project routes to its own database even when the caller carries no org")
    void anonymousCaller_routesToTheTenantDatabase() throws Exception {
        DynamicDataSourceManager manager = mock(DynamicDataSourceManager.class);
        Connection connection = mock(Connection.class);
        DataSource tenant = answering(connection);
        when(manager.getDataSource(null, "proj-a")).thenReturn(tenant);
        DynamicRoutingDataSource routing = new DynamicRoutingDataSource(manager, null);

        TenantContext.setTenantId("proj-a");

        assertThat(routing.getConnection()).isSameAs(connection);
    }

    @Test
    @DisplayName("without a tenant the configured database answers")
    void noTenant_usesTheDefaultDatabase() throws Exception {
        Connection connection = mock(Connection.class);
        DynamicRoutingDataSource routing = new DynamicRoutingDataSource(null, answering(connection));

        assertThat(routing.getConnection()).isSameAs(connection);
    }

    @Test
    @DisplayName("a tenant with no database manager falls back to the configured database")
    void tenantWithoutManager_usesTheDefaultDatabase() throws Exception {
        Connection connection = mock(Connection.class);
        DynamicRoutingDataSource routing = new DynamicRoutingDataSource(null, answering(connection));

        TenantContext.setTenantId("proj-a");

        assertThat(routing.getConnection()).isSameAs(connection);
    }

    @Test
    @DisplayName("multi-tenant mode refuses a connection that names no project")
    void noDefaultAndNoTenant_refuses() {
        DynamicRoutingDataSource routing = new DynamicRoutingDataSource(mock(DynamicDataSourceManager.class), null);

        assertThatThrownBy(routing::getConnection).isInstanceOf(SQLException.class)
                .hasMessageContaining("No default datasource configured");
        assertThatThrownBy(() -> routing.getConnection("user", "pw")).isInstanceOf(SQLException.class);
    }
}
