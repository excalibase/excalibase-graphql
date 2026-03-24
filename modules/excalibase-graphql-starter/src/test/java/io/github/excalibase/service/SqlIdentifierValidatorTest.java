package io.github.excalibase.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlIdentifierValidatorTest {

    // ── validate ────────────────────────────────────────────────────

    @Test
    void validate_acceptsSimpleName() {
        assertThat(SqlIdentifierValidator.validate("users")).isEqualTo("users");
    }

    @Test
    void validate_acceptsSnakeCase() {
        assertThat(SqlIdentifierValidator.validate("order_items")).isEqualTo("order_items");
    }

    @Test
    void validate_acceptsNameWithSpaces() {
        assertThat(SqlIdentifierValidator.validate("zip code")).isEqualTo("zip code");
    }

    @Test
    void validate_acceptsNameWithHyphen() {
        assertThat(SqlIdentifierValidator.validate("my-table")).isEqualTo("my-table");
    }

    @Test
    void validate_acceptsNameWithDot() {
        assertThat(SqlIdentifierValidator.validate("public.users")).isEqualTo("public.users");
    }

    @Test
    void validate_rejectsNull() {
        assertThatThrownBy(() -> SqlIdentifierValidator.validate(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_rejectsEmpty() {
        assertThatThrownBy(() -> SqlIdentifierValidator.validate(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_rejectsSqlInjection() {
        assertThatThrownBy(() -> SqlIdentifierValidator.validate("users; DROP TABLE users--"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_rejectsBackticks() {
        assertThatThrownBy(() -> SqlIdentifierValidator.validate("users`"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_rejectsQuotes() {
        assertThatThrownBy(() -> SqlIdentifierValidator.validate("users\""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_rejectsParentheses() {
        assertThatThrownBy(() -> SqlIdentifierValidator.validate("users()"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── quoteMysql ──────────────────────────────────────────────────

    @Test
    void quoteMysql_wrapsInBackticks() {
        assertThat(SqlIdentifierValidator.quoteMysql("users")).isEqualTo("`users`");
    }

    @Test
    void quoteMysql_handlesSnakeCase() {
        assertThat(SqlIdentifierValidator.quoteMysql("order_items")).isEqualTo("`order_items`");
    }

    @Test
    void quoteMysql_handlesSpaces() {
        assertThat(SqlIdentifierValidator.quoteMysql("zip code")).isEqualTo("`zip code`");
    }

    // ── quotePostgres ───────────────────────────────────────────────

    @Test
    void quotePostgres_wrapsInDoubleQuotes() {
        assertThat(SqlIdentifierValidator.quotePostgres("users")).isEqualTo("\"users\"");
    }

    @Test
    void quotePostgres_handlesSnakeCase() {
        assertThat(SqlIdentifierValidator.quotePostgres("order_items")).isEqualTo("\"order_items\"");
    }

    @Test
    void quotePostgres_handlesSpaces() {
        assertThat(SqlIdentifierValidator.quotePostgres("zip code")).isEqualTo("\"zip code\"");
    }
}
