package io.github.excalibase.security;

import io.github.excalibase.config.datasource.DynamicDataSourceManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnownProjectsTest {

    private static final String NO_TENANTS = "";

    private static UnknownProjectCache unknownCache() {
        return new UnknownProjectCache(100, java.time.Duration.ofSeconds(30), System::currentTimeMillis);
    }

    @Test
    @DisplayName("single-database mode with no project id refuses to start")
    void singleDatabase_withoutProjectId_refuses() {
        assertThatThrownBy(() -> KnownProjects.forDeployment("", NO_TENANTS, null, unknownCache()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.project-id");
        assertThatThrownBy(() -> KnownProjects.forDeployment(null, NO_TENANTS, null, unknownCache()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("single-database mode knows exactly the pinned project")
    void singleDatabase_knowsOnlyThePinnedProject() {
        KnownProjects projects = KnownProjects.forDeployment("proj-a", NO_TENANTS, null, unknownCache());

        assertThatCode(() -> projects.require("proj-a")).doesNotThrowAnyException();
        assertThatThrownBy(() -> projects.require("proj-b")).isInstanceOf(UnknownProjectException.class);
    }

    @Test
    @DisplayName("multi-tenant mode without tenant routing (authentication off) refuses to start")
    void multiTenant_withoutRouting_refuses() {
        assertThatThrownBy(() -> KnownProjects.forDeployment("", "http://provisioning/api", null, unknownCache()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt-enabled");
    }

    @Test
    @DisplayName("multi-tenant mode with a pinned project id is ambiguous and refuses to start")
    void multiTenant_withPinnedProject_refuses() {
        DynamicDataSourceManager manager = mock(DynamicDataSourceManager.class);
        assertThatThrownBy(() -> KnownProjects.forDeployment("proj-a", "http://provisioning/api", manager, unknownCache()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.project-id");
    }

    @Test
    @DisplayName("multi-tenant mode knows a project when its database resolves, with or without an org")
    void multiTenant_resolvesThroughTheTenantDatabase() {
        DynamicDataSourceManager manager = mock(DynamicDataSourceManager.class);
        when(manager.getDataSource(null, "proj-gone")).thenThrow(new UnknownProjectException("proj-gone"));
        KnownProjects projects = KnownProjects.forDeployment("", "http://provisioning/api", manager, unknownCache());

        assertThatCode(() -> projects.require("proj-a")).doesNotThrowAnyException();
        verify(manager).getDataSource(null, "proj-a");
        assertThatThrownBy(() -> projects.require("proj-gone")).isInstanceOf(UnknownProjectException.class);
    }
}
