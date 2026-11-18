package io.github.excalibase.admin;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AdminGuardTest {

    private static final String SECRET = "guard-secret-0123456789-abcdefghijklmnop";
    private static final String IP = "198.51.100.7";

    private final FailedAttempts attempts = new FailedAttempts(3, Duration.ofMinutes(1), 100, System::nanoTime);
    private final AdminGuard guard = new AdminGuard(AdminSecret.of(SECRET), attempts);

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
}
