package io.github.excalibase.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Pins how {@link JwtAuthFilter#extractProjectId} reads the project from the URL path. */
class JwtAuthFilterTest {

    private static String projectIdFor(String uri) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn(uri);
        return JwtAuthFilter.extractProjectId(req);
    }

    @Test @DisplayName("project-scoped GraphQL path → projectId")
    void scopedGraphql() {
        assertThat(projectIdFor("/proj-237qoqksdb/graphql")).isEqualTo("proj-237qoqksdb");
    }

    @Test @DisplayName("legacy unscoped GraphQL path → null")
    void legacyGraphql() {
        assertThat(projectIdFor("/graphql")).isNull();
    }

    @Test @DisplayName("project-scoped REST path → projectId")
    void scopedRest() {
        assertThat(projectIdFor("/proj-abc/api/v1/users")).isEqualTo("proj-abc");
        assertThat(projectIdFor("/proj-abc/api/v1/rpc/do_thing")).isEqualTo("proj-abc");
    }

    @Test @DisplayName("legacy unscoped REST path → null")
    void legacyRest() {
        assertThat(projectIdFor("/api/v1/users")).isNull();
        assertThat(projectIdFor("/api/v1/rpc/do_thing")).isNull();
    }

    @Test @DisplayName("multi-segment prefix is not a projectId (opaque single segment only)")
    void multiSegmentRejected() {
        assertThat(projectIdFor("/org/proj/graphql")).isNull();
    }

    @Test @DisplayName("unrelated paths → null")
    void unrelated() {
        assertThat(projectIdFor("/healthz")).isNull();
        assertThat(projectIdFor(null)).isNull();
    }
}
