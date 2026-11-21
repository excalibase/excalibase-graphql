package io.github.excalibase.permissions.compile;

import io.github.excalibase.permissions.PermissionEvaluationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LikePatternTest {

    private static boolean matches(String pattern, String value) {
        return LikePattern.compile(pattern).matches(value);
    }

    @Test
    void wildcardsEscapesAndNewlines() {
        assertThat(matches("ab%", "abcdef")).isTrue();
        assertThat(matches("a_c", "abc")).isTrue();
        assertThat(matches("a_c", "abbc")).isFalse();
        assertThat(matches("abc", "abcd")).isFalse();
        assertThat(matches("a%", "a\nb")).isTrue();
        assertThat(matches("a_b", "a\nb")).isTrue();
        assertThat(matches("abc", "abc\n")).isFalse();
        assertThat(matches("50\\%", "50%")).isTrue();
        assertThat(matches("50\\%", "500")).isFalse();
        assertThat(matches("a\\_c", "abc")).isFalse();
        assertThat(matches("a\\\\c", "a\\c")).isTrue();
        assertThat(matches("a\\bc", "abc")).isTrue();
        assertThat(matches("%", "")).isTrue();
        assertThat(matches("", "")).isTrue();
        assertThat(matches("", "a")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"+", "*", "?", "(", ")", "[", "]", "{", "}", "^", "$", "|", "."})
    void metacharactersAreLiteral(String meta) {
        assertThat(matches("a" + meta + "b", "a" + meta + "b")).isTrue();
        assertThat(matches("a" + meta + "b", "axb")).isFalse();
    }

    @Test
    void codePointsNotChars() {
        String emoji = new String(Character.toChars(0x1F600));
        assertThat(matches("a_b", "a" + emoji + "b")).isTrue();
        assertThat(matches("a__b", "a" + emoji + "b")).isFalse();
    }

    @Test
    void aTrailingEscapeIsRefused() {
        assertThatThrownBy(() -> LikePattern.compile("abc\\")).isInstanceOf(PermissionEvaluationException.class);
        assertThat(matches("abc\\\\", "abc\\")).isTrue();
    }

    @Test
    void aPatternOverTheLengthCapIsRefused() {
        assertThat(LikePattern.compile("a".repeat(4096)).matches("a".repeat(4096))).isTrue();
        assertThatThrownBy(() -> LikePattern.compile("a".repeat(4097)))
                .isInstanceOf(PermissionEvaluationException.class)
                .hasMessageContaining("too long");
    }

    @Test
    void backtrackingStaysLinear() {
        String value = "a".repeat(100_000);
        assertThat(millis(() -> assertThat(matches("%%%%%%%%x", value)).isFalse())).isLessThan(2000);
        assertThat(millis(() -> assertThat(matches("%a%a%a%a%b", value)).isFalse())).isLessThan(2000);
        assertThat(millis(() -> assertThat(matches("%a%a%a%a%a", value)).isTrue())).isLessThan(2000);
    }

    private static long millis(Runnable action) {
        long start = System.nanoTime();
        action.run();
        return (System.nanoTime() - start) / 1_000_000;
    }
}
