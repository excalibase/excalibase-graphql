package io.github.excalibase.security;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RlsViolationExceptionTest {

    @Test
    void carriesStableCodeAndStructuredContext() {
        RlsViolationException denied = new RlsViolationException("INSERT", "rls_demo.notes");

        assertThat(denied.code()).isEqualTo("RLS_DENIED");
        assertThat(denied.operation()).isEqualTo("INSERT");
        assertThat(denied.table()).isEqualTo("rls_demo.notes");
        assertThat(denied.policy()).isEmpty();
    }

    @Test
    void messageIsShortAndFreeOfSqlText() {
        RlsViolationException denied = new RlsViolationException("UPDATE", "rls_demo.notes");

        assertThat(denied.getMessage())
                .contains("UPDATE").contains("rls_demo.notes")
                .doesNotContain("ERROR:").doesNotContain("violates").doesNotContain("SQLSTATE");
    }

    @Test
    void detailsIncludePolicyOnlyWhenKnown() {
        RlsViolationException anonymous = new RlsViolationException("DELETE", "rls_demo.notes");
        RlsViolationException named = new RlsViolationException("DELETE", "rls_demo.notes", "owner-notes");

        assertThat(anonymous.details()).containsExactlyInAnyOrderEntriesOf(
                Map.of("operation", "DELETE", "table", "rls_demo.notes"));
        assertThat(named.details()).containsExactlyInAnyOrderEntriesOf(
                Map.of("operation", "DELETE", "table", "rls_demo.notes", "policy", "owner-notes"));
        assertThat(named.policy()).contains("owner-notes");
    }

    @Test
    void findUnwrapsTheCauseChain() {
        RlsViolationException denied = new RlsViolationException("INSERT", "rls_demo.notes");
        RuntimeException wrapped = new RuntimeException("outer", new IllegalStateException("mid", denied));

        assertThat(RlsViolationException.find(wrapped)).contains(denied);
        assertThat(RlsViolationException.find(new SQLException("ERROR: something else"))).isEmpty();
        assertThat(RlsViolationException.find(null)).isEmpty();
    }
}
