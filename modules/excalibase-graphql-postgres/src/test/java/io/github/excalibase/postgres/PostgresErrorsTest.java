package io.github.excalibase.postgres;

import io.github.excalibase.errors.DataError;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresErrorsTest {

    private final PostgresDialect dialect = new PostgresDialect();

    /** A server error as the driver receives it: fields separated by NUL, each led by its one-letter tag. */
    private static PSQLException server(String state, String message, String... extra) {
        StringBuilder fields = new StringBuilder("SERROR\0VERROR\0C").append(state).append('\0')
                .append('M').append(message).append('\0');
        for (String field : extra) {
            fields.append(field).append('\0');
        }
        return new PSQLException(new ServerErrorMessage(fields.toString()));
    }

    private DataError describe(SQLException failure) {
        return dialect.describeError(failure).orElseThrow();
    }

    @Test
    void aUniqueViolation_isAConflictNamingTheConstraint() {
        DataError error = describe(server("23505", "duplicate key value violates unique constraint \"users_email_key\"",
                "DKey (email)=(a@b.c) already exists.", "nusers_email_key", "tusers", "spublic"));

        assertThat(error.status()).isEqualTo(409);
        assertThat(error.code()).isEqualTo(DataError.UNIQUE_VIOLATION);
        assertThat(error.constraint()).isEqualTo("users_email_key");
        assertThat(error.message()).isEqualTo("duplicate key value violates unique constraint \"users_email_key\"");
    }

    @Test
    void foreignKeyCheckNotNullAndExclusion_areBadRequests() {
        assertThat(describe(server("23503", "insert or update on table \"orders\" violates foreign key constraint \"fk\"",
                "nfk"))).extracting(DataError::status, DataError::code, DataError::constraint)
                .containsExactly(400, DataError.FOREIGN_KEY_VIOLATION, "fk");
        assertThat(describe(server("23514", "new row for relation \"orders\" violates check constraint \"positive\"",
                "npositive"))).extracting(DataError::code, DataError::constraint)
                .containsExactly(DataError.CHECK_VIOLATION, "positive");
        assertThat(describe(server("23502", "null value in column \"name\" of relation \"t\" violates not-null constraint",
                "cname"))).extracting(DataError::code, DataError::column)
                .containsExactly(DataError.NOT_NULL_VIOLATION, "name");
        assertThat(describe(server("23P01", "conflicting key value violates exclusion constraint \"no_overlap\"",
                "nno_overlap"))).extracting(DataError::status, DataError::code)
                .containsExactly(400, DataError.EXCLUSION_VIOLATION);
    }

    @Test
    void aRaise_answersOnlyTheRaisedMessage() {
        DataError error = describe(server("P0001", "Not enough stock for product 7",
                "WPL/pgSQL function reserve_stock() line 15 at RAISE", "Fpl_exec.c", "L3911", "Rexec_stmt_raise"));

        assertThat(error.status()).isEqualTo(400);
        assertThat(error.code()).isEqualTo(DataError.RAISED_EXCEPTION);
        assertThat(error.message()).isEqualTo("Not enough stock for product 7");
        assertThat(error.restBody().toString()).doesNotContain("PL/pgSQL").doesNotContain("line 15");
    }

    @Test
    void aMalformedValue_isAnInvalidValue() {
        DataError error = describe(server("22P02", "invalid input syntax for type uuid: \"nope\""));

        assertThat(error.status()).isEqualTo(400);
        assertThat(error.code()).isEqualTo(DataError.INVALID_VALUE);
        assertThat(error.message()).isEqualTo("invalid input syntax for type uuid: \"nope\"");
    }

    @Test
    void anInsufficientPrivilege_isForbidden() {
        DataError error = describe(server("42501", "permission denied for table secrets"));

        assertThat(error.status()).isEqualTo(403);
        assertThat(error.code()).isEqualTo(DataError.PERMISSION_DENIED);
    }

    @Test
    void aServerFault_isNoClientError() {
        assertThat(dialect.describeError(server("XX000", "could not open file \"base/1/2\": No such file"))).isEmpty();
        assertThat(dialect.describeError(server("42P01", "relation \"x\" does not exist"))).isEmpty();
    }

    @Test
    void aFailureWithoutServerDetail_fallsBackToTheSqlState() {
        DataError error = describe(new SQLException("ERROR: duplicate key\n  Where: SQL statement", "23505"));

        assertThat(error.status()).isEqualTo(409);
        assertThat(error.message()).doesNotContain("Where");
    }
}
