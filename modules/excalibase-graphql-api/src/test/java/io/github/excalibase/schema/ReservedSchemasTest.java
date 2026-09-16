package io.github.excalibase.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the platform-owned schema denylist. The built-in entries must hold
 * regardless of configuration — a misconfiguration must never expose a platform schema.
 */
class ReservedSchemasTest {

    @Test
    @DisplayName("isReserved returns true for every built-in platform schema")
    void isReserved_builtInSchema_returnsTrue() {
        ReservedSchemas reserved = ReservedSchemas.fromConfig("");

        assertThat(reserved.isReserved("auth")).isTrue();
        assertThat(reserved.isReserved("excalibase")).isTrue();
    }

    @Test
    @DisplayName("isReserved returns false for an ordinary tenant schema")
    void isReserved_tenantSchema_returnsFalse() {
        ReservedSchemas reserved = ReservedSchemas.fromConfig("");

        assertThat(reserved.isReserved("public")).isFalse();
        assertThat(reserved.isReserved("kanban")).isFalse();
        assertThat(reserved.isReserved("rls_demo")).isFalse();
    }

    @Test
    @DisplayName("isReserved matches case-insensitively so a quoted variant cannot slip through")
    void isReserved_differentCase_returnsTrue() {
        ReservedSchemas reserved = ReservedSchemas.fromConfig("");

        assertThat(reserved.isReserved("AUTH")).isTrue();
        assertThat(reserved.isReserved("Excalibase")).isTrue();
    }

    @Test
    @DisplayName("fromConfig adds extra schemas listed in configuration")
    void fromConfig_extraSchemas_addsThemToDenylist() {
        ReservedSchemas reserved = ReservedSchemas.fromConfig("billing, internal_ops ");

        assertThat(reserved.isReserved("billing")).isTrue();
        assertThat(reserved.isReserved("internal_ops")).isTrue();
    }

    @Test
    @DisplayName("fromConfig keeps built-ins even when configuration lists unrelated schemas")
    void fromConfig_extraSchemas_keepsBuiltIns() {
        ReservedSchemas reserved = ReservedSchemas.fromConfig("billing");

        assertThat(reserved.names()).contains("auth", "excalibase", "billing");
    }

    @Test
    @DisplayName("configuration cannot remove a built-in reserved schema")
    void fromConfig_attemptToOverrideBuiltIns_stillReservesThem() {
        ReservedSchemas onlyPublic = ReservedSchemas.fromConfig("public");
        ReservedSchemas negated = ReservedSchemas.fromConfig("-auth,!excalibase");

        assertThat(onlyPublic.isReserved("auth")).isTrue();
        assertThat(onlyPublic.isReserved("excalibase")).isTrue();
        assertThat(negated.isReserved("auth")).isTrue();
        assertThat(negated.isReserved("excalibase")).isTrue();
    }

    @Test
    @DisplayName("names is immutable so no caller can drop a built-in at runtime")
    void names_mutationAttempt_throws() {
        ReservedSchemas reserved = ReservedSchemas.fromConfig("");

        assertThat(reserved.names()).isUnmodifiable();
    }

    @Test
    @DisplayName("filter drops reserved schemas and preserves the order of the rest")
    void filter_discoveredSchemas_removesReservedOnes() {
        ReservedSchemas reserved = ReservedSchemas.fromConfig("billing");

        List<String> visible = reserved.filter(
                List.of("auth", "excalibase", "billing", "public", "hana", "kanban"));

        assertThat(visible).containsExactly("public", "hana", "kanban");
    }
}
