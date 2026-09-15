package io.github.excalibase.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the one routing rule every project-scoped surface shares: the project is
 * the single leading path segment of {@code /{projectId}/graphql} and
 * {@code /{projectId}/api/v1/...}; anything else has no project.
 */
class ProjectPathTest {

    @Test
    @DisplayName("GraphQL, REST and realtime paths resolve the leading segment")
    void resolvesProjectScopedPaths() {
        assertThat(ProjectPath.fromUri("/proj-abc/graphql")).isEqualTo("proj-abc");
        assertThat(ProjectPath.fromUri("/proj-abc/api/v1/customer")).isEqualTo("proj-abc");
        assertThat(ProjectPath.fromUri("/proj-abc/api/v1/realtime")).isEqualTo("proj-abc");
        assertThat(ProjectPath.fromUri("/proj-abc/api/v1")).isEqualTo("proj-abc");
    }

    @Test
    @DisplayName("unscoped and platform paths have no project")
    void unscopedPathsHaveNoProject() {
        assertThat(ProjectPath.fromUri("/graphql")).isNull();
        assertThat(ProjectPath.fromUri("/api/v1/customer")).isNull();
        assertThat(ProjectPath.fromUri("/actuator/health")).isNull();
        assertThat(ProjectPath.fromUri("/")).isNull();
        assertThat(ProjectPath.fromUri(null)).isNull();
    }

    @Test
    @DisplayName("only a single opaque segment counts as a project")
    void rejectsMultiSegmentPrefixes() {
        assertThat(ProjectPath.fromUri("/org/proj-abc/graphql")).isNull();
        assertThat(ProjectPath.fromUri("//graphql")).isNull();
        assertThat(ProjectPath.fromUri("proj-abc/graphql")).isNull();
    }
}
