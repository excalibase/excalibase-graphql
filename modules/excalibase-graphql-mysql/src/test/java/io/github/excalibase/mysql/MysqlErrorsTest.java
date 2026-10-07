package io.github.excalibase.mysql;

import io.github.excalibase.errors.DataError;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;

import static org.assertj.core.api.Assertions.assertThat;

class MysqlErrorsTest {

    private final MysqlDialect dialect = new MysqlDialect();

    private DataError describe(SQLException failure) {
        return dialect.describeError(failure).orElseThrow();
    }

    @Test
    void aDuplicateEntry_isAConflictNamingTheKey() {
        DataError error = describe(new SQLIntegrityConstraintViolationException(
                "Duplicate entry 'a@b.c' for key 'users.email_unique'", "23000", 1062));

        assertThat(error.status()).isEqualTo(409);
        assertThat(error.code()).isEqualTo(DataError.UNIQUE_VIOLATION);
        assertThat(error.constraint()).isEqualTo("email_unique");
        assertThat(error.message()).doesNotContain("users.");
    }

    @Test
    void aForeignKeyFailure_namesTheConstraintButNoSchema() {
        DataError error = describe(new SQLIntegrityConstraintViolationException(
                "Cannot add or update a child row: a foreign key constraint fails (`appdb`.`orders`, CONSTRAINT "
                        + "`orders_customer_fk` FOREIGN KEY (`customer_id`) REFERENCES `customers` (`id`))",
                "23000", 1452));

        assertThat(error.status()).isEqualTo(400);
        assertThat(error.code()).isEqualTo(DataError.FOREIGN_KEY_VIOLATION);
        assertThat(error.constraint()).isEqualTo("orders_customer_fk");
        assertThat(error.message()).doesNotContain("appdb");
        assertThat(describe(new SQLIntegrityConstraintViolationException("Cannot delete or update a parent row: "
                + "a foreign key constraint fails (`appdb`.`orders`, CONSTRAINT `fk2` FOREIGN KEY (`c`))", "23000", 1451))
                .constraint()).isEqualTo("fk2");
    }

    @Test
    void checkAndNotNull_areBadRequests() {
        assertThat(describe(new SQLException("Check constraint 'positive_total' is violated.", "HY000", 3819)))
                .extracting(DataError::code, DataError::constraint)
                .containsExactly(DataError.CHECK_VIOLATION, "positive_total");
        assertThat(describe(new SQLIntegrityConstraintViolationException("Column 'name' cannot be null", "23000", 1048)))
                .extracting(DataError::code, DataError::column)
                .containsExactly(DataError.NOT_NULL_VIOLATION, "name");
        assertThat(describe(new SQLException("Field 'name' doesn't have a default value", "HY000", 1364)))
                .extracting(DataError::code, DataError::column)
                .containsExactly(DataError.NOT_NULL_VIOLATION, "name");
    }

    @Test
    void aSignal_answersItsMessage() {
        DataError error = describe(new SQLException("Not enough stock", "45000", 1644));

        assertThat(error.code()).isEqualTo(DataError.RAISED_EXCEPTION);
        assertThat(error.message()).isEqualTo("Not enough stock");
    }

    @Test
    void anIncorrectValue_isAnInvalidValue() {
        assertThat(describe(new SQLException("Incorrect integer value: 'x' for column 'age' at row 1", "HY000", 1366)))
                .extracting(DataError::code, DataError::column)
                .containsExactly(DataError.INVALID_VALUE, "age");
        assertThat(describe(new SQLException("Data too long for column 'name' at row 1", "22001", 1406)).code())
                .isEqualTo(DataError.INVALID_VALUE);
    }

    @Test
    void aDeniedCommand_isForbiddenWithoutTheDatabaseUser() {
        DataError error = describe(new SQLException(
                "INSERT command denied to user 'app'@'10.0.0.5' for table 'secrets'", "42000", 1142));

        assertThat(error.status()).isEqualTo(403);
        assertThat(error.message()).doesNotContain("10.0.0.5").doesNotContain("'app'");
    }

    @Test
    void aServerFault_isNoClientError() {
        assertThat(dialect.describeError(new SQLException("Lock wait timeout exceeded", "HY000", 1205))).isEmpty();
        assertThat(dialect.describeError(new SQLException("Table 'x' doesn't exist", "42S02", 1146))).isEmpty();
    }
}
