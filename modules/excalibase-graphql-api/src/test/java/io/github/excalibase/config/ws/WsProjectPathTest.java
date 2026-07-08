package io.github.excalibase.config.ws;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WsProjectPathTest {

    @Test
    @DisplayName("extracts the single project segment for the graphql WS path")
    void graphqlPath() {
        assertThat(WsProjectPath.projectId("/proj_a3k9/graphql", WsProjectPath.GRAPHQL_SUFFIX)).isEqualTo("proj_a3k9");
        assertThat(WsProjectPath.projectId("/proj_a3k9/graphql")).isEqualTo("proj_a3k9");
    }

    @Test
    @DisplayName("extracts the project segment for the realtime WS path")
    void realtimePath() {
        assertThat(WsProjectPath.projectId("/app-a/api/v1/realtime", WsProjectPath.REALTIME_SUFFIX)).isEqualTo("app-a");
        assertThat(WsProjectPath.projectId("/app-a/api/v1/realtime")).isEqualTo("app-a");
    }

    @Test
    @DisplayName("rejects the legacy unscoped path (no project segment)")
    void unscopedRejected() {
        assertThat(WsProjectPath.projectId("/graphql")).isNull();
        assertThat(WsProjectPath.projectId("/api/v1/realtime")).isNull();
        assertThat(WsProjectPath.projectId("/graphql", WsProjectPath.GRAPHQL_SUFFIX)).isNull();
    }

    @Test
    @DisplayName("rejects a multi-segment project (projectId is a single opaque segment)")
    void multiSegmentRejected() {
        assertThat(WsProjectPath.projectId("/org/proj/graphql", WsProjectPath.GRAPHQL_SUFFIX)).isNull();
        assertThat(WsProjectPath.projectId("/a/b/api/v1/realtime", WsProjectPath.REALTIME_SUFFIX)).isNull();
    }

    @Test
    @DisplayName("returns null for a non-WS path")
    void nonWsPath() {
        assertThat(WsProjectPath.projectId("/proj/rest")).isNull();
        assertThat(WsProjectPath.projectId(null)).isNull();
    }
}
