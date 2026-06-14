package io.github.excalibase.rls.jdbc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlIdentifierTest {

    @Test @DisplayName("plain snake_case column accepted")
    void plain_accepted() {
        assertThat(SqlIdentifier.checkColumn("user_id")).isEqualTo("user_id");
        assertThat(SqlIdentifier.checkColumn("CamelCase")).isEqualTo("CamelCase");
        assertThat(SqlIdentifier.checkColumn("_internal")).isEqualTo("_internal");
        assertThat(SqlIdentifier.checkColumn("col1")).isEqualTo("col1");
    }

    @Test @DisplayName("quote wraps with SQL-standard double quotes")
    void quote_doubleQuotes() {
        assertThat(SqlIdentifier.quote("user_id")).isEqualTo("\"user_id\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",                                    // empty
        "1col",                                // starts with digit
        "col with space",                      // whitespace
        "col-name",                            // hyphen
        "users.id",                            // dotted path (we don't support nested in JDBC)
        "id; DROP TABLE users",                // SQL injection
        "id\"--",                              // embedded quote
        "id`",                                 // backtick
        "id'",                                 // single quote
        "id/*comment*/",                       // comment
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" // 65 chars, over the 63-char limit
    })
    @DisplayName("malicious or malformed identifiers rejected")
    void rejected(String identifier) {
        assertThatThrownBy(() -> SqlIdentifier.checkColumn(identifier))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Rejected unsafe SQL identifier");
    }

    @Test @DisplayName("null rejected")
    void null_rejected() {
        assertThatThrownBy(() -> SqlIdentifier.checkColumn(null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
