package io.github.excalibase.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.concurrent.atomic.AtomicLong;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminGuardTest {

    private static final String SECRET = "guard-secret-0123456789-abcdefghijklmnop";
    private static final String IP = "198.51.100.7";

    private final AdminGuard.FailedAttempts attempts = new AdminGuard.FailedAttempts(3, Duration.ofMinutes(1), 100, System::nanoTime);
    private final AdminGuard guard = new AdminGuard(AdminGuard.Secret.of(SECRET), attempts);

    @Test
    void theRightSecretIsAllowed() {
        assertThat(guard.check(IP, SECRET)).isEqualTo(AdminGuard.Verdict.ALLOWED);
    }

    @Test
    void aMissingSecretIsUnauthorizedAndCounts() {
        assertThat(guard.check(IP, null)).isEqualTo(AdminGuard.Verdict.UNAUTHORIZED);
        assertThat(attempts.tracked()).isEqualTo(1);
    }

    @Test
    void aWrongSecretIsUnauthorized() {
        assertThat(guard.check(IP, "wrong")).isEqualTo(AdminGuard.Verdict.UNAUTHORIZED);
    }

    @Test
    void anAlmostRightSecretIsUnauthorized() {
        assertThat(guard.check(IP, SECRET + "!")).isEqualTo(AdminGuard.Verdict.UNAUTHORIZED);
        assertThat(guard.check(IP, SECRET.substring(1))).isEqualTo(AdminGuard.Verdict.UNAUTHORIZED);
    }

    @Test
    void afterTooManyFailuresEvenTheRightSecretIsLimited() {
        guard.check(IP, "a");
        guard.check(IP, "b");
        guard.check(IP, "c");

        assertThat(guard.check(IP, SECRET)).isEqualTo(AdminGuard.Verdict.LIMITED);
        assertThat(guard.check("198.51.100.8", SECRET)).isEqualTo(AdminGuard.Verdict.ALLOWED);
    }

    @Test
    void aSuccessClearsTheCountSoAnOwnerWhoMistypedIsNotLockedOut() {
        guard.check(IP, "a");
        guard.check(IP, "b");
        guard.check(IP, SECRET);
        guard.check(IP, "c");
        guard.check(IP, "d");

        assertThat(guard.check(IP, SECRET)).isEqualTo(AdminGuard.Verdict.ALLOWED);
    }

    @Test
    void toStringNeverShowsTheSecret() {
        assertThat(guard.toString()).doesNotContain(SECRET);
    }


    private static final String SECRET_TEXT = "correct-horse-battery-staple-0123456789";

    @Test
    void secretOfTheMinimumLengthIsAccepted() {
        assertThat(AdminGuard.Secret.of("x".repeat(AdminGuard.Secret.MIN_LENGTH))).isNotNull();
    }

    @Test
    void shorterThanTheMinimumIsRefusedWithoutEchoingIt() {
        String tooShort = "s".repeat(AdminGuard.Secret.MIN_LENGTH - 1);

        assertThatThrownBy(() -> AdminGuard.Secret.of(tooShort))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least " + AdminGuard.Secret.MIN_LENGTH)
                .hasMessageNotContaining(tooShort);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankIsRefused(String blank) {
        assertThatThrownBy(() -> AdminGuard.Secret.of(blank)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullIsRefused() {
        assertThatThrownBy(() -> AdminGuard.Secret.of(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void surroundingWhitespaceIsNotPartOfTheSecret() {
        assertThat(AdminGuard.Secret.of("  " + SECRET_TEXT + "\n").matches(SECRET_TEXT)).isTrue();
    }

    @Test
    void controlAndNonAsciiCharactersAreRefusedBecauseAHeaderCannotCarryThem() {
        String withTab = "a".repeat(20) + "\t" + "b".repeat(20);
        String withUmlaut = "a".repeat(20) + "ü" + "b".repeat(20);

        assertThatThrownBy(() -> AdminGuard.Secret.of(withTab)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AdminGuard.Secret.of(withUmlaut)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void matchesOnlyTheExactSecret() {
        AdminGuard.Secret secret = AdminGuard.Secret.of(SECRET_TEXT);

        assertThat(secret.matches(SECRET_TEXT)).isTrue();
        assertThat(secret.matches(SECRET_TEXT.substring(0, SECRET_TEXT.length() - 1) + "X")).isFalse();
        assertThat(secret.matches(SECRET_TEXT.substring(0, SECRET_TEXT.length() - 1))).isFalse();
        assertThat(secret.matches(SECRET_TEXT + "x")).isFalse();
        assertThat(secret.matches(SECRET_TEXT.toUpperCase())).isFalse();
        assertThat(secret.matches("")).isFalse();
        assertThat(secret.matches(null)).isFalse();
    }

    @Test
    void secretToStringIsRedacted() {
        assertThat(AdminGuard.Secret.of(SECRET_TEXT).toString()).doesNotContain(SECRET_TEXT).doesNotContain("correct");
    }


    private static final Duration FAILURE_WINDOW = Duration.ofSeconds(60);

    private final AtomicLong nanos = new AtomicLong();

    private AdminGuard.FailedAttempts limiter(int maxFailures, int maxTracked) {
        return new AdminGuard.FailedAttempts(maxFailures, FAILURE_WINDOW, maxTracked, nanos::get);
    }

    private void failTimes(AdminGuard.FailedAttempts limiter, String ip, int times) {
        for (int i = 0; i < times; i++) {
            limiter.failure(ip);
        }
    }

    @Test
    void anAddressWithNoFailuresIsNotBlocked() {
        assertThat(limiter(3, 100).blocked("10.0.0.1")).isFalse();
    }

    @Test
    void blocksOnceTheFailuresReachTheLimit() {
        AdminGuard.FailedAttempts limiter = limiter(3, 100);

        failTimes(limiter, "10.0.0.1", 2);
        assertThat(limiter.blocked("10.0.0.1")).isFalse();

        limiter.failure("10.0.0.1");
        assertThat(limiter.blocked("10.0.0.1")).isTrue();
    }

    @Test
    void oneAddressBlockedDoesNotBlockAnother() {
        AdminGuard.FailedAttempts limiter = limiter(2, 100);

        failTimes(limiter, "10.0.0.1", 2);

        assertThat(limiter.blocked("10.0.0.1")).isTrue();
        assertThat(limiter.blocked("10.0.0.2")).isFalse();
    }

    @Test
    void theBlockLiftsWhenTheWindowHasPassed() {
        AdminGuard.FailedAttempts limiter = limiter(2, 100);
        failTimes(limiter, "10.0.0.1", 2);

        nanos.addAndGet(FAILURE_WINDOW.toNanos() + 1);

        assertThat(limiter.blocked("10.0.0.1")).isFalse();
    }

    @Test
    void failuresOutsideTheWindowDoNotAccumulate() {
        AdminGuard.FailedAttempts limiter = limiter(3, 100);
        failTimes(limiter, "10.0.0.1", 2);
        nanos.addAndGet(FAILURE_WINDOW.toNanos() + 1);

        limiter.failure("10.0.0.1");

        assertThat(limiter.blocked("10.0.0.1")).isFalse();
    }

    @Test
    void successForgetsTheFailures() {
        AdminGuard.FailedAttempts limiter = limiter(3, 100);
        failTimes(limiter, "10.0.0.1", 2);

        limiter.success("10.0.0.1");
        limiter.failure("10.0.0.1");

        assertThat(limiter.blocked("10.0.0.1")).isFalse();
        assertThat(limiter.tracked()).isEqualTo(1);
    }

    @Test
    void memoryIsBoundedWhateverTheNumberOfAddresses() {
        AdminGuard.FailedAttempts limiter = limiter(3, 50);

        for (int i = 0; i < 5_000; i++) {
            limiter.failure("203.0.113." + i);
        }

        assertThat(limiter.tracked()).isLessThanOrEqualTo(50);
    }

    @Test
    void anAddressStillBlockedIsKeptWhileOldQuietOnesAreDropped() {
        AdminGuard.FailedAttempts limiter = limiter(2, 3);
        failTimes(limiter, "attacker", 2);

        for (int i = 0; i < 10; i++) {
            limiter.failure("bystander-" + i);
            limiter.blocked("attacker");
        }

        assertThat(limiter.blocked("attacker")).isTrue();
    }
}
