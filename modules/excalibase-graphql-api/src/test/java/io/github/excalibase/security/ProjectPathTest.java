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

    @Test
    @DisplayName("EXC-737: a REST table named like a route word is a REST path of its project, not the GraphQL route")
    void reservedWordTablesAreRestPaths() {
        assertThat(ProjectPath.fromUri("/proj-abc/api/v1/graphql")).isEqualTo("proj-abc");
        assertThat(ProjectPath.fromUri("/proj-abc/api/v1/realtime")).isEqualTo("proj-abc");
        assertThat(ProjectPath.isGraphql("/proj-abc/api/v1/graphql")).isFalse();
        assertThat(ProjectPath.isGraphql("/proj-abc/graphql")).isTrue();
    }

    @Test
    @DisplayName("a path that only contains a route word somewhere is not project-shaped")
    void routeWordsElsewhereAreNoProject() {
        assertThat(ProjectPath.fromUri("/proj-abc/x/graphql")).isNull();
        assertThat(ProjectPath.fromUri("/proj-abc/x/api/v1/orders")).isNull();
        assertThat(ProjectPath.fromUri("/proj-abc/api/v1x")).isNull();
        assertThat(ProjectPath.fromUri("/proj-abc/graphql/")).isNull();
        assertThat(ProjectPath.isGraphql("/proj-abc/x/graphql")).isFalse();
    }
}
