package io.github.excalibase.config.datasource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DynamicRoutingDataSourceTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("a project routes to its own database even when the caller carries no org")
    void anonymousCaller_routesToTheTenantDatabase() throws Exception {
        DynamicDataSourceManager manager = mock(DynamicDataSourceManager.class);
        DataSource tenant = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(manager.getDataSource(null, "proj-a")).thenReturn(tenant);
        when(tenant.getConnection()).thenReturn(connection);
        DynamicRoutingDataSource routing = new DynamicRoutingDataSource(manager, false);
        routing.setTargetDataSources(Map.of());
        routing.afterPropertiesSet();

        TenantContext.setTenantId("proj-a");

        assertThat(routing.getConnection()).isSameAs(connection);
    }
}
