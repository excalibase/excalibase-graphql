package io.github.excalibase.errors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ColumnValueCheckTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "uuid|a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11",
            "uuid|A0EEBC99-9C0B-4EF8-BB6D-6BB9BD380A11",
            "uuid|{a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11}",
            "uuid|a0eebc999c0b4ef8bb6d6bb9bd380a11",
            "integer|42",
            "int4|-7",
            "bigint|+9000000000",
            "int|3",
            "smallint|1",
            "numeric|19.99",
            "decimal|1e5",
            "double precision|NaN",
            "real|-Infinity",
            "text|anything",
            "timestamp with time zone|not checked here",
    })
    void wellFormedValues_pass(String type, String value) {
        assertThatCode(() -> ColumnValueCheck.require("t", "c", type, value)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "uuid|not-a-uuid",
            "uuid|a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a1",
            "integer|abc",
            "bigint|1.5",
            "numeric|ten",
            "float8|1,5",
    })
    void malformedValues_areInvalidValuesNamingTheColumn(String type, String value) {
        assertThatThrownBy(() -> ColumnValueCheck.require("public.orders", "ref", type, value))
                .isInstanceOf(DataErrorException.class)
                .hasMessageContaining("'ref'")
                .satisfies(thrown -> {
                    DataError error = ((DataErrorException) thrown).error();
                    assertThat(error.code()).isEqualTo(DataError.INVALID_VALUE);
                    assertThat(error.column()).isEqualTo("ref");
                    assertThat(error.status()).isEqualTo(400);
                });
    }

    @Test
    void nonStringValues_andUnknownTypes_pass() {
        assertThatCode(() -> ColumnValueCheck.require("t", "c", "integer", BigInteger.TEN)).doesNotThrowAnyException();
        assertThatCode(() -> ColumnValueCheck.require("t", "c", null, "x")).doesNotThrowAnyException();
        assertThatCode(() -> ColumnValueCheck.require("t", "c", "uuid", null)).doesNotThrowAnyException();
    }
}
