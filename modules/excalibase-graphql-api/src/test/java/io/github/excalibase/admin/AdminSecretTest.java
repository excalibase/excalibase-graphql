package io.github.excalibase.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminSecretTest {

    private static final String SECRET = "correct-horse-battery-staple-0123456789";

    @Test
    void secretOfTheMinimumLengthIsAccepted() {
        assertThat(AdminSecret.of("x".repeat(AdminSecret.MIN_LENGTH))).isNotNull();
    }

    @Test
    void shorterThanTheMinimumIsRefusedWithoutEchoingIt() {
        String tooShort = "s".repeat(AdminSecret.MIN_LENGTH - 1);

        assertThatThrownBy(() -> AdminSecret.of(tooShort))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least " + AdminSecret.MIN_LENGTH)
                .hasMessageNotContaining(tooShort);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankIsRefused(String blank) {
        assertThatThrownBy(() -> AdminSecret.of(blank)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullIsRefused() {
        assertThatThrownBy(() -> AdminSecret.of(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void surroundingWhitespaceIsNotPartOfTheSecret() {
        assertThat(AdminSecret.of("  " + SECRET + "\n").matches(SECRET)).isTrue();
    }

    @Test
    void controlAndNonAsciiCharactersAreRefusedBecauseAHeaderCannotCarryThem() {
        String withTab = "a".repeat(20) + "\t" + "b".repeat(20);
        String withUmlaut = "a".repeat(20) + "ü" + "b".repeat(20);

        assertThatThrownBy(() -> AdminSecret.of(withTab)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AdminSecret.of(withUmlaut)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void matchesOnlyTheExactSecret() {
        AdminSecret secret = AdminSecret.of(SECRET);

        assertThat(secret.matches(SECRET)).isTrue();
        assertThat(secret.matches(SECRET.substring(0, SECRET.length() - 1) + "X")).isFalse();
        assertThat(secret.matches(SECRET.substring(0, SECRET.length() - 1))).isFalse();
        assertThat(secret.matches(SECRET + "x")).isFalse();
        assertThat(secret.matches(SECRET.toUpperCase())).isFalse();
        assertThat(secret.matches("")).isFalse();
        assertThat(secret.matches(null)).isFalse();
    }

    @Test
    void toStringNeverShowsTheSecret() {
        assertThat(AdminSecret.of(SECRET).toString()).doesNotContain(SECRET).doesNotContain("correct");
    }
}
