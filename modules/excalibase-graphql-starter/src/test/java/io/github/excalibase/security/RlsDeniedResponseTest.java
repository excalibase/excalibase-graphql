package io.github.excalibase.security;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RlsDeniedResponseTest {

    private final RlsViolationException denied = new RlsViolationException("INSERT", "rls_demo.notes");

    @Test
    void graphqlErrorCarriesCodeInExtensions() {
        Map<String, Object> error = RlsDeniedResponse.graphqlError(denied);

        assertThat(error.get("message")).isEqualTo(denied.getMessage());
        assertThat(error.get("extensions")).isEqualTo(Map.of(
                "code", "RLS_DENIED", "operation", "INSERT", "table", "rls_demo.notes"));
    }

    @Test
    void graphqlErrorIncludesPolicyWhenKnown() {
        Map<String, Object> error = RlsDeniedResponse.graphqlError(
                new RlsViolationException("UPDATE", "rls_demo.notes", "owner-notes"));

        assertThat(error.get("extensions")).isEqualTo(Map.of(
                "code", "RLS_DENIED", "operation", "UPDATE", "table", "rls_demo.notes", "policy", "owner-notes"));
    }

    @Test
    void restBodyFollowsPostgrestErrorShape() {
        Map<String, Object> body = RlsDeniedResponse.restBody(denied);

        assertThat(body.get("code")).isEqualTo("RLS_DENIED");
        assertThat(body.get("message")).isEqualTo(denied.getMessage());
        assertThat(body.get("error")).isEqualTo(denied.getMessage());
        assertThat(body.get("details")).isEqualTo(Map.of("operation", "INSERT", "table", "rls_demo.notes"));
    }

    @Test
    void neitherShapeLeaksSqlText() {
        String graphql = RlsDeniedResponse.graphqlError(denied).toString();
        String rest = RlsDeniedResponse.restBody(denied).toString();

        assertThat(graphql).doesNotContain("ERROR:").doesNotContain("violates").doesNotContain("SQLSTATE");
        assertThat(rest).doesNotContain("ERROR:").doesNotContain("violates").doesNotContain("SQLSTATE");
    }
}
