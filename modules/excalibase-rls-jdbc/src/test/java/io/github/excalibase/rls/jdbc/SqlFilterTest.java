package io.github.excalibase.rls.jdbc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SqlFilterTest {

    private static final SqlFilter HAS_RLS =
        new SqlFilter("\"user_id\" = :rls_p0", Map.of("rls_p0", "alice"));

    @Test
    @DisplayName("UNRESTRICTED has empty sql and empty params")
    void unrestricted_isEmpty() {
        assertThat(SqlFilter.UNRESTRICTED.sql()).isEmpty();
        assertThat(SqlFilter.UNRESTRICTED.params()).isEmpty();
        assertThat(SqlFilter.UNRESTRICTED.isUnrestricted()).isTrue();
    }

    @Test
    @DisplayName("DENY_ALL is 1=0 with empty params")
    void denyAll_isOneEqualsZero() {
        assertThat(SqlFilter.DENY_ALL.sql()).isEqualTo("1=0");
        assertThat(SqlFilter.DENY_ALL.params()).isEmpty();
        assertThat(SqlFilter.DENY_ALL.isUnrestricted()).isFalse();
    }

    @Test
    @DisplayName("composeWhere: empty userWhere + UNRESTRICTED → no WHERE clause at all")
    void composeWhere_bothEmpty_returnsEmptyString() {
        assertThat(SqlFilter.UNRESTRICTED.composeWhere("")).isEmpty();
        assertThat(SqlFilter.UNRESTRICTED.composeWhere(null)).isEmpty();
        assertThat(SqlFilter.UNRESTRICTED.composeWhere("  ")).isEmpty();
    }

    @Test
    @DisplayName("composeWhere: empty userWhere + filter → \" WHERE <rls>\"")
    void composeWhere_onlyRls_emitsRls() {
        assertThat(HAS_RLS.composeWhere(null)).isEqualTo(" WHERE \"user_id\" = :rls_p0");
        assertThat(HAS_RLS.composeWhere("")).isEqualTo(" WHERE \"user_id\" = :rls_p0");
        assertThat(HAS_RLS.composeWhere("   ")).isEqualTo(" WHERE \"user_id\" = :rls_p0");
    }

    @Test
    @DisplayName("composeWhere: userWhere + UNRESTRICTED → \" WHERE (<userWhere>)\"")
    void composeWhere_onlyUserWhere_wrapsInParens() {
        assertThat(SqlFilter.UNRESTRICTED.composeWhere("status = :s"))
            .isEqualTo(" WHERE (status = :s)");
    }

    @Test
    @DisplayName("composeWhere: userWhere + filter → \" WHERE (<userWhere>) AND (<rls>)\"")
    void composeWhere_both_andJoined() {
        assertThat(HAS_RLS.composeWhere("status = :s AND amount > :min"))
            .isEqualTo(" WHERE (status = :s AND amount > :min) AND (\"user_id\" = :rls_p0)");
    }

    @Test
    @DisplayName("composeWhere: DENY_ALL is treated as a normal filter (1=0 emitted, returns zero rows)")
    void composeWhere_denyAll_emits1Equals0() {
        assertThat(SqlFilter.DENY_ALL.composeWhere(null)).isEqualTo(" WHERE 1=0");
        assertThat(SqlFilter.DENY_ALL.composeWhere("status = :s"))
            .isEqualTo(" WHERE (status = :s) AND (1=0)");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n", "  \t \n  "})
    @DisplayName("composeWhere treats null/empty/whitespace userWhere identically")
    void composeWhere_blankUserWhere_treatedAsEmpty(String blank) {
        assertThat(HAS_RLS.composeWhere(blank)).isEqualTo(" WHERE \"user_id\" = :rls_p0");
    }

    @Test
    @DisplayName("constructor with null sql/params produces an UNRESTRICTED-equivalent")
    void constructor_nullInputs_normalized() {
        SqlFilter f = new SqlFilter(null, null);
        assertThat(f.sql()).isEmpty();
        assertThat(f.params()).isEmpty();
        assertThat(f.isUnrestricted()).isTrue();
    }

    @Test
    @DisplayName("params() returns an unmodifiable view")
    void params_areImmutable() {
        Map<String, Object> params = HAS_RLS.params();
        org.junit.jupiter.api.Assertions.assertThrows(
            UnsupportedOperationException.class,
            () -> params.put("evil", "value")
        );
    }
}
