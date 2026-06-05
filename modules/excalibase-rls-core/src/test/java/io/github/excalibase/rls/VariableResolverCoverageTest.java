package io.github.excalibase.rls;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Targets the FieldType cast branches the composition tests don't reach. */
class VariableResolverCoverageTest {

    private static final UserContext CTX = new UserContext() {
        @Override public String userId() { return UUID.randomUUID().toString(); }
        @Override public String tenantId() { return null; }
        @Override public Set<String> roles() { return Set.of(); }
        @Override public Set<String> groupIds() { return Set.of(); }
    };

    private final VariableResolver r = new VariableResolver(CTX);

    @Test @DisplayName("LONG literal")
    void longCast() { assertThat(r.resolve("999999999999", FieldType.LONG)).isEqualTo(999999999999L); }

    @Test @DisplayName("DOUBLE literal")
    void doubleCast() { assertThat(r.resolve("3.14", FieldType.DOUBLE)).isEqualTo(3.14d); }

    @Test @DisplayName("BOOLEAN literal")
    void booleanCast() { assertThat(r.resolve("true", FieldType.BOOLEAN)).isEqualTo(true); }

    @Test @DisplayName("DATE literal")
    void dateCast() {
        assertThat(r.resolve("2026-05-31", FieldType.DATE)).isEqualTo(LocalDate.of(2026, java.time.Month.MAY, 31));
    }

    @Test @DisplayName("DATETIME literal — LocalDateTime shape")
    void datetimeCast_localDateTimeShape() {
        Object v = r.resolve("2026-05-31T12:00:00", FieldType.DATETIME);
        assertThat(v).isInstanceOf(Instant.class);
    }

    @Test @DisplayName("DATETIME literal — ISO_INSTANT shape (Instant.toString())")
    void datetimeCast_isoInstantShape() {
        Object v = r.resolve("2026-05-31T12:00:00Z", FieldType.DATETIME);
        assertThat(v).isInstanceOf(Instant.class);
    }

    @Test @DisplayName("null inputs to resolveList → empty list")
    void resolveList_null_empty() {
        assertThat(r.resolveList(null, FieldType.STRING)).isEmpty();
    }

    @Test @DisplayName("resolveList wraps a single non-collection resolved variable as a one-element list")
    void resolveList_singletonVariable_wrapped() {
        UserContext ctx = new UserContext() {
            @Override public String userId() { return "u1"; }
            @Override public String tenantId() { return null; }
            @Override public Set<String> roles() { return Set.of(); }
            @Override public Set<String> groupIds() { return Set.of(); }
            @Override public Object resolveVariable(String name) {
                return "x-" + name; // returns a non-collection
            }
        };
        VariableResolver r2 = new VariableResolver(ctx);
        java.util.Collection<Object> v = r2.resolveList("{{somekey}}", FieldType.STRING);
        assertThat(v).containsExactly("x-somekey");
    }

    @Test @DisplayName("currentUserId is null when context's userId is null")
    void currentUserId_nullCtx() {
        UserContext nullCtx = new UserContext() {
            @Override public String userId() { return null; }
            @Override public String tenantId() { return null; }
            @Override public Set<String> roles() { return Set.of(); }
            @Override public Set<String> groupIds() { return Set.of(); }
        };
        assertThat(new VariableResolver(nullCtx).resolve("{{currentUserId}}", FieldType.UUID)).isNull();
    }
}
