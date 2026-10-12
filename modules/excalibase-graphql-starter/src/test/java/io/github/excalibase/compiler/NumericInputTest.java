package io.github.excalibase.compiler;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;

class NumericInputTest {

    @Test
    void anIntegerColumn_takesItsNumberSentAsAString() {
        assertThat(NumericInput.coerce("bigint", "9007199254740993")).isEqualTo(9007199254740993L);
        assertThat(NumericInput.coerce("int4", " +42 ")).isEqualTo(42L);
        assertThat(NumericInput.coerce("numeric", "99999999999999999999"))
                .isEqualTo(new BigDecimal("99999999999999999999"));
        assertThat(NumericInput.coerce("bigint", "99999999999999999999"))
                .isEqualTo(new BigInteger("99999999999999999999"));
    }

    @Test
    void anythingElse_isLeftForTheDatabase() {
        assertThat(NumericInput.coerce("bigint", "abc")).isEqualTo("abc");
        assertThat(NumericInput.coerce("text", "42")).isEqualTo("42");
        assertThat(NumericInput.coerce(null, "42")).isEqualTo("42");
        assertThat(NumericInput.coerce("bigint", 7)).isEqualTo(7);
        assertThat(NumericInput.coerce("bigint", null)).isNull();
    }
}
