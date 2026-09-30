package io.github.excalibase.permissions.compile;

import io.github.excalibase.permissions.Literal;
import io.github.excalibase.permissions.PermissionEvaluationException;
import io.github.excalibase.permissions.SessionVariable;
import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.github.excalibase.permissions.compile.FixtureSchema.NOTES;
import static io.github.excalibase.permissions.compile.FixtureSchema.U1;
import static io.github.excalibase.permissions.compile.FixtureSchema.U2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Session variables and literals bind to the column's type, and fail loudly when they cannot (spec §2). */
class SessionBindingTest {

    private static final SchemaInfo SCHEMA = FixtureSchema.schema();

    private static ColumnType type(String column) {
        return ColumnType.of(SCHEMA, NOTES, column);
    }

    private static SessionVariable variable(String name) {
        return new SessionVariable(name);
    }

    @Test
    void variableNamesAreCaseInsensitive() {
        SessionBinding binding = new SessionBinding(Map.of("X-Excalibase-User-Id", U1));

        assertThat(binding.value(variable("x-excalibase-USER-id"), type("owner_id"))).isEqualTo(UUID.fromString(U1));
    }

    @Test
    void missingVariable_failsWithMissingSessionVariable() {
        SessionBinding binding = new SessionBinding(Map.of());

        assertThatThrownBy(() -> binding.value(variable("X-Excalibase-User-Id"), type("owner_id")))
                .isInstanceOf(PermissionEvaluationException.class)
                .extracting("code").isEqualTo("missing_session_variable");
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource({
            "owner_id, not-a-uuid",
            "org_id, 12abc",
            "org_id, 99999999999",
            "views, 1.5",
            "amount, ten",
            "pinned, maybe",
            "created_at, 2026-01-01T10:00:00",
            "due, 2026-13-01",
            "local_at, yesterday"
    })
    void unconvertibleVariable_failsWithInvalidSessionVariable(String column, String value) {
        SessionBinding binding = new SessionBinding(Map.of("x-excalibase-value", value));

        assertThatThrownBy(() -> binding.value(variable("X-Excalibase-Value"), type(column)))
                .isInstanceOf(PermissionEvaluationException.class)
                .extracting("code").isEqualTo("invalid_session_variable");
    }

    @Test
    void unconvertibleLiteral_failsWithInvalidLiteral() {
        SessionBinding binding = new SessionBinding(Map.of());

        assertThatThrownBy(() -> binding.value(new Literal(true), type("org_id")))
                .isInstanceOf(PermissionEvaluationException.class)
                .extracting("code").isEqualTo("invalid_literal");
    }

    @ParameterizedTest(name = "{0} <- {1}")
    @CsvSource({
            "org_id, 42",
            "views, 9000000000",
            "amount, 10.50",
            "pinned, true",
            "pinned, f",
            "title, hello",
            "created_at, 2026-03-01 12:30:00+02",
            "created_at, 2026-03-01T10:30:00Z",
            "due, 2026-06-01",
            "local_at, 2026-06-01 08:00:00",
            "local_at, 2026-06-01",
            "mood, ok",
            "ip, 10.0.0.1",
            "score, 1.25"
    })
    void convertibleVariables_bind(String column, String value) {
        SessionBinding binding = new SessionBinding(Map.of("x-excalibase-value", value));

        assertThat(binding.value(variable("X-Excalibase-Value"), type(column))).isNotNull();
    }

    @Test
    void valuesBindToTheColumnsJavaType() {
        SessionBinding binding = new SessionBinding(Map.of(
                "x-excalibase-ts", "2026-03-01 12:30:00+02", "x-excalibase-n", "10.50"));

        assertThat(binding.value(variable("X-Excalibase-Ts"), type("created_at")))
                .isEqualTo(Instant.parse("2026-03-01T10:30:00Z"));
        assertThat(binding.value(variable("X-Excalibase-N"), type("amount"))).isEqualTo(new BigDecimal("10.50"));
        assertThat(binding.value(new Literal(7L), type("org_id"))).isEqualTo(7L);
        assertThat(binding.value(new Literal("2026-06-01"), type("due"))).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(binding.value(new Literal("2026-06-01"), type("local_at")))
                .isEqualTo(LocalDateTime.of(2026, 6, 1, 0, 0));
    }

    @Test
    void arrayVariable_bindsEveryElement() {
        SessionBinding binding = new SessionBinding(Map.of("x-excalibase-ids", "{\"" + U1 + "\",\"" + U2 + "\"}"));

        assertThat(binding.values(variable("X-Excalibase-Ids"), type("owner_id")))
                .containsExactly(UUID.fromString(U1), UUID.fromString(U2));
    }

    @Test
    void arrayVariable_readsQuotingEscapesAndUnquotedNull() {
        SessionBinding binding = new SessionBinding(Map.of("x-excalibase-tags", "{\"a,b\",\"q\\\"t\", plain ,NULL}"));

        assertThat(binding.values(variable("X-Excalibase-Tags"), type("title")))
                .isEqualTo(Arrays.asList("a,b", "q\"t", "plain", null));
    }

    @Test
    void emptyArrayVariable_bindsNoValues() {
        SessionBinding binding = new SessionBinding(Map.of("x-excalibase-ids", "{}"));

        assertThat(binding.values(variable("X-Excalibase-Ids"), type("owner_id"))).isEmpty();
    }

    @Test
    void scalarVariableInAList_isOneElement() {
        SessionBinding binding = new SessionBinding(Map.of("x-excalibase-user-id", U1));

        assertThat(binding.values(variable("X-Excalibase-User-Id"), type("owner_id")))
                .containsExactly(UUID.fromString(U1));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"{\"a\"", "{{1,2}}", "{\"a\"x}", "{\"" + U1 + "\",nope}"})
    void malformedOrMistypedArray_failsWithInvalidSessionVariable(String raw) {
        SessionBinding binding = new SessionBinding(Map.of("x-excalibase-ids", raw));

        assertThatThrownBy(() -> binding.values(variable("X-Excalibase-Ids"), type("owner_id")))
                .isInstanceOf(PermissionEvaluationException.class)
                .extracting("code").isEqualTo("invalid_session_variable");
    }

    @Test
    void literalList_bindsEveryElement() {
        SessionBinding binding = new SessionBinding(Map.of());

        assertThat(binding.values(new Literal(List.of(1L, "2")), type("org_id"))).containsExactly(1L, 2L);
    }

    @Test
    void unknownColumn_failsWithUnknownColumn() {
        assertThatThrownBy(() -> ColumnType.of(SCHEMA, NOTES, "nope"))
                .isInstanceOf(PermissionEvaluationException.class)
                .extracting("code").isEqualTo("unknown_column");
    }
}
