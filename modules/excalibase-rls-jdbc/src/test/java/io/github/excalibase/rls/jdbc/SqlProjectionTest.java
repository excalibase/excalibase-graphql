package io.github.excalibase.rls.jdbc;

import io.github.excalibase.rls.Assignment;
import io.github.excalibase.rls.ColumnPolicy;
import io.github.excalibase.rls.MaskMode;
import io.github.excalibase.rls.Operation;
import io.github.excalibase.rls.UserContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link JdbcEvaluator#project} → {@link SqlProjection}.
 *
 * <p>Asserts the exact SQL the projection emits for each MaskMode the engine
 * supports in v1.6 (HIDE, NULL). PARTIAL/HASH/CUSTOM are documented v1.7
 * work; they throw {@link UnsupportedOperationException} at compile time.
 */
class SqlProjectionTest {


    @Nested
    @DisplayName("composition / selectList shape")
    class Composition {

        @Test
        @DisplayName("no policies → every requested column passes through unchanged")
        void noPolicies_passThrough() {
            JdbcEvaluator e = new JdbcEvaluator(List.of(), List.of());
            SqlProjection p = e.project("users", anon(), Operation.SELECT,
                List.of("id", "email", "name"));
            assertThat(p.selectList()).containsExactly("\"id\"", "\"email\"", "\"name\"");
            assertThat(p.hidden()).isEmpty();
            assertThat(p.params()).isEmpty();
        }

        @Test
        @DisplayName("HIDE → column dropped from selectList and listed in hidden()")
        void hide_dropsFromSelectList() {
            JdbcEvaluator e = new JdbcEvaluator(List.of(), List.of(
                hide("hide-pw", "users", Set.of("password_hash"), Assignment.all())));
            SqlProjection p = e.project("users", anon(), Operation.SELECT,
                List.of("id", "email", "password_hash"));
            assertThat(p.selectList()).containsExactly("\"id\"", "\"email\"");
            assertThat(p.hidden()).containsExactly("password_hash");
        }

        @Test
        @DisplayName("NULL → column emitted as \"NULL AS col\"")
        void nullMode_emitsNullAs() {
            JdbcEvaluator e = new JdbcEvaluator(List.of(), List.of(
                nullify("null-email", "users", Set.of("email"), Assignment.all())));
            SqlProjection p = e.project("users", anon(), Operation.SELECT,
                List.of("id", "email"));
            assertThat(p.selectList()).containsExactly("\"id\"", "NULL AS \"email\"");
            assertThat(p.hidden()).isEmpty();
        }

        @Test
        @DisplayName("mixed HIDE + NULL + untouched")
        void mixedModes() {
            JdbcEvaluator e = new JdbcEvaluator(List.of(), List.of(
                hide("h", "users", Set.of("password_hash"), Assignment.all()),
                nullify("n", "users", Set.of("ssn"), Assignment.all())));
            SqlProjection p = e.project("users", anon(), Operation.SELECT,
                List.of("id", "email", "ssn", "password_hash", "name"));
            assertThat(p.selectList())
                .containsExactly("\"id\"", "\"email\"", "NULL AS \"ssn\"", "\"name\"");
            assertThat(p.hidden()).containsExactly("password_hash");
        }

        @Test
        @DisplayName("policy on a different resource is ignored")
        void wrongResource_ignored() {
            JdbcEvaluator e = new JdbcEvaluator(List.of(), List.of(
                hide("h", "audit_log", Set.of("ip"), Assignment.all())));
            SqlProjection p = e.project("users", anon(), Operation.SELECT, List.of("id", "ip"));
            assertThat(p.selectList()).containsExactly("\"id\"", "\"ip\"");
            assertThat(p.hidden()).isEmpty();
        }
    }

    @Nested
    @DisplayName("composeSelect()")
    class ComposeSelect {

        @Test @DisplayName("joins selectList with comma-space")
        void joinsWithComma() {
            JdbcEvaluator e = new JdbcEvaluator(List.of(), List.of(
                nullify("n", "users", Set.of("email"), Assignment.all())));
            SqlProjection p = e.project("users", anon(), Operation.SELECT,
                List.of("id", "email", "name"));
            assertThat(p.composeSelect())
                .isEqualTo("\"id\", NULL AS \"email\", \"name\"");
        }

        @Test @DisplayName("empty requested column list returns empty string")
        void emptyRequested_emptyString() {
            JdbcEvaluator e = new JdbcEvaluator(List.of(), List.of());
            SqlProjection p = e.project("users", anon(), Operation.SELECT, List.of());
            assertThat(p.composeSelect()).isEmpty();
        }

        @Test @DisplayName("all columns hidden → empty selectList → empty composeSelect")
        void allHidden_emptyComposeSelect() {
            JdbcEvaluator e = new JdbcEvaluator(List.of(), List.of(
                hide("h", "users", Set.of("id", "email"), Assignment.all())));
            SqlProjection p = e.project("users", anon(), Operation.SELECT, List.of("id", "email"));
            assertThat(p.selectList()).isEmpty();
            assertThat(p.composeSelect()).isEmpty();
        }
    }

    @Nested
    @DisplayName("identifier safety")
    class IdentifierSafety {

        @Test @DisplayName("requested column with unsafe name rejected")
        void unsafeRequestedColumn_rejected() {
            JdbcEvaluator e = new JdbcEvaluator(List.of(), List.of());
            List<String> requestedColumns = List.of("id; DROP TABLE users");
            var anonCtx = anon();
            assertThatThrownBy(() -> e.project("users", anonCtx, Operation.SELECT, requestedColumns))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Rejected");
        }
    }

    @Nested
    @DisplayName("v1.6 deferral — PARTIAL/HASH/CUSTOM")
    class DeferredModes {

        @Test @DisplayName("PARTIAL mode throws at project() time")
        void partial_throws() {
            ColumnPolicy partial = new ColumnPolicy(
                UUID.randomUUID().toString(), "partial", "users", Set.of("email"),
                Operation.ALL, MaskMode.PARTIAL,
                new io.github.excalibase.rls.PartialMaskSpec.KeepLast(4, '*'),
                null, 0, true, List.of(Assignment.all()));
            JdbcEvaluator e = new JdbcEvaluator(List.of(), List.of(partial));
            List<String> requestedColumns = List.of("email");
            var anonCtx = anon();
            assertThatThrownBy(() -> e.project("users", anonCtx, Operation.SELECT, requestedColumns))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("PARTIAL");
        }

        @Test @DisplayName("HASH mode throws at project() time")
        void hash_throws() {
            ColumnPolicy hash = new ColumnPolicy(
                UUID.randomUUID().toString(), "hash", "users", Set.of("email"),
                Operation.ALL, MaskMode.HASH, null, null, 0, true, List.of(Assignment.all()));
            JdbcEvaluator e = new JdbcEvaluator(List.of(), List.of(hash));
            List<String> requestedColumns = List.of("email");
            var anonCtx = anon();
            assertThatThrownBy(() -> e.project("users", anonCtx, Operation.SELECT, requestedColumns))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("HASH");
        }

        @Test @DisplayName("CUSTOM mode throws at project() time")
        void custom_throws() {
            ColumnPolicy custom = new ColumnPolicy(
                UUID.randomUUID().toString(), "custom", "users", Set.of("email"),
                Operation.ALL, MaskMode.CUSTOM, null, "my-masker", 0, true, List.of(Assignment.all()));
            JdbcEvaluator e = new JdbcEvaluator(List.of(), List.of(custom));
            List<String> requestedColumns = List.of("email");
            var anonCtx = anon();
            assertThatThrownBy(() -> e.project("users", anonCtx, Operation.SELECT, requestedColumns))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("CUSTOM");
        }
    }

    // ----- helpers -----

    private static ColumnPolicy hide(String name, String resource, Set<String> cols, Assignment a) {
        return new ColumnPolicy(
            UUID.randomUUID().toString(), name, resource, cols, Operation.ALL,
            MaskMode.HIDE, null, null, 0, true, List.of(a));
    }

    private static ColumnPolicy nullify(String name, String resource, Set<String> cols, Assignment a) {
        return new ColumnPolicy(
            UUID.randomUUID().toString(), name, resource, cols, Operation.ALL,
            MaskMode.NULL, null, null, 0, true, List.of(a));
    }

    private static UserContext anon() {
        return new UserContext() {
            @Override public String userId() { return null; }
            @Override public String tenantId() { return null; }
            @Override public Set<String> roles() { return Set.of("anon"); }
            @Override public Set<String> groupIds() { return Set.of(); }
        };
    }
}
