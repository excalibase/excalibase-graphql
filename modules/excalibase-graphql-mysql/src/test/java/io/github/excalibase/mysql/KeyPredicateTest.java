package io.github.excalibase.mysql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KeyPredicateTest {

    @Test
    @DisplayName("no keys match nothing")
    void noKeys() {
        assertThat(KeyPredicate.of(List.of("a.`id`"), List.of(), new MapSqlParameterSource(), "k")).isEqualTo("FALSE");
    }

    @Test
    @DisplayName("a single-column key is an IN list of bound values")
    void singleColumn() {
        MapSqlParameterSource params = new MapSqlParameterSource();

        String sql = KeyPredicate.of(List.of("a.`id`"), List.of(List.of(1), List.of(2)), params, "k");

        assertThat(sql).isEqualTo("a.`id` IN (:k_0_0, :k_1_0)");
        assertThat(params.getValue("k_1_0")).isEqualTo(2);
    }

    @Test
    @DisplayName("a composite key compares row constructors")
    void compositeKey() {
        MapSqlParameterSource params = new MapSqlParameterSource();

        String sql = KeyPredicate.of(List.of("a.`w`", "a.`c`"), List.of(List.of("north", "A-1")), params, "k");

        assertThat(sql).isEqualTo("(a.`w`, a.`c`) IN ((:k_0_0, :k_0_1))");
        assertThat(params.getValue("k_0_1")).isEqualTo("A-1");
    }
}
