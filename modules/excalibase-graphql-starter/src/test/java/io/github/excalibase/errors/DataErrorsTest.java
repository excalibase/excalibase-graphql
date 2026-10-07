package io.github.excalibase.errors;

import io.github.excalibase.SqlDialect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;

class DataErrorsTest {

    private final SqlDialect generic = Mockito.mock(SqlDialect.class, CALLS_REAL_METHODS);

    private static SQLException state(String sqlState) {
        return new SQLException("ERROR: secret internals\n  Where: PL/pgSQL function f() line 15 at RAISE", sqlState);
    }

    @Test
    @DisplayName("a unique violation is a 409 conflict, without the driver's text")
    void uniqueViolation_isConflict() {
        DataError error = DataErrors.describe(new RuntimeException(state("23505")), generic).orElseThrow();

        assertThat(error.status()).isEqualTo(409);
        assertThat(error.code()).isEqualTo(DataError.UNIQUE_VIOLATION);
        assertThat(error.message()).doesNotContain("secret").doesNotContain("Where");
    }

    @Test
    void integrityAndDataClasses_areBadRequests() {
        assertThat(DataErrors.describe(state("23503"), generic).orElseThrow().code())
                .isEqualTo(DataError.FOREIGN_KEY_VIOLATION);
        assertThat(DataErrors.describe(state("23514"), generic).orElseThrow().code())
                .isEqualTo(DataError.CHECK_VIOLATION);
        assertThat(DataErrors.describe(state("23502"), generic).orElseThrow().code())
                .isEqualTo(DataError.NOT_NULL_VIOLATION);
        assertThat(DataErrors.describe(state("23P01"), generic).orElseThrow().code())
                .isEqualTo(DataError.EXCLUSION_VIOLATION);
        assertThat(DataErrors.describe(state("23000"), generic).orElseThrow().code())
                .isEqualTo(DataError.CONSTRAINT_VIOLATION);
        DataError invalid = DataErrors.describe(state("22P02"), generic).orElseThrow();
        assertThat(invalid.code()).isEqualTo(DataError.INVALID_VALUE);
        assertThat(invalid.status()).isEqualTo(400);
    }

    @Test
    void aServerFault_isNotAClientError() {
        assertThat(DataErrors.describe(state("XX000"), generic)).isEmpty();
        assertThat(DataErrors.describe(state("08006"), generic)).isEmpty();
        assertThat(DataErrors.describe(new SQLException("no state"), generic)).isEmpty();
        assertThat(DataErrors.describe(new IllegalStateException("boom"), generic)).isEmpty();
    }

    @Test
    void anErrorTheEngineRaised_isReturnedAsIs() {
        DataError raised = DataError.invalidValue("id", "Invalid value for column 'id'");

        assertThat(DataErrors.describe(new RuntimeException(new DataErrorException(raised)), generic))
                .contains(raised);
    }

    @Test
    void theDialectDecides_whenItKnowsTheError() {
        SqlDialect dialect = Mockito.mock(SqlDialect.class);
        DataError custom = DataError.badRequest(DataError.RAISED_EXCEPTION, "Not enough stock");
        Mockito.when(dialect.describeError(Mockito.any())).thenReturn(Optional.of(custom));

        assertThat(DataErrors.describe(new RuntimeException(state("P0001")), dialect)).contains(custom);
    }

    @Test
    void withoutADialect_theSqlStateDecides() {
        assertThat(DataErrors.describe(state("23505"), null).orElseThrow().status()).isEqualTo(409);
    }

    @Test
    void restBody_carriesCodeMessageAndWhatTheErrorNames() {
        DataError error = DataError.conflict(DataError.UNIQUE_VIOLATION, "duplicate", "users_email_key");

        assertThat(error.restBody()).containsEntry("code", "unique_violation")
                .containsEntry("message", "duplicate")
                .containsEntry("error", "duplicate")
                .containsEntry("constraint", "users_email_key")
                .doesNotContainKey("column");
    }

    @Test
    @SuppressWarnings("unchecked")
    void graphqlError_carriesTheCodeInExtensions() {
        DataError error = DataError.invalidValue("created_at", "Invalid value for column 'created_at'");

        Map<String, Object> extensions = (Map<String, Object>) error.graphqlError().get("extensions");
        assertThat(error.graphqlError()).containsEntry("message", "Invalid value for column 'created_at'");
        assertThat(extensions).containsEntry("code", "invalid_value").containsEntry("column", "created_at")
                .doesNotContainKey("constraint");
    }

    @Test
    void withColumn_namesTheColumnOnce() {
        DataError error = DataError.badRequest(DataError.INVALID_VALUE, "invalid input syntax for type uuid: \"x\"");

        DataError named = error.withColumn("id");
        assertThat(named.column()).isEqualTo("id");
        assertThat(named.message()).isEqualTo("Invalid value for column 'id': invalid input syntax for type uuid: \"x\"");
        assertThat(named.withColumn("other")).isEqualTo(named);
    }
}
