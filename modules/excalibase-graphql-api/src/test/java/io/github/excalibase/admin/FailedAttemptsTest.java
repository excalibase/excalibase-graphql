package io.github.excalibase.admin;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class FailedAttemptsTest {

    private static final Duration WINDOW = Duration.ofSeconds(60);

    private final AtomicLong nanos = new AtomicLong();

    private FailedAttempts limiter(int maxFailures, int maxTracked) {
        return new FailedAttempts(maxFailures, WINDOW, maxTracked, nanos::get);
    }

    private void failTimes(FailedAttempts limiter, String ip, int times) {
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
        FailedAttempts limiter = limiter(3, 100);

        failTimes(limiter, "10.0.0.1", 2);
        assertThat(limiter.blocked("10.0.0.1")).isFalse();

        limiter.failure("10.0.0.1");
        assertThat(limiter.blocked("10.0.0.1")).isTrue();
    }

    @Test
    void oneAddressBlockedDoesNotBlockAnother() {
        FailedAttempts limiter = limiter(2, 100);

        failTimes(limiter, "10.0.0.1", 2);

        assertThat(limiter.blocked("10.0.0.1")).isTrue();
        assertThat(limiter.blocked("10.0.0.2")).isFalse();
    }

    @Test
    void theBlockLiftsWhenTheWindowHasPassed() {
        FailedAttempts limiter = limiter(2, 100);
        failTimes(limiter, "10.0.0.1", 2);

        nanos.addAndGet(WINDOW.toNanos() + 1);

        assertThat(limiter.blocked("10.0.0.1")).isFalse();
    }

    @Test
    void failuresOutsideTheWindowDoNotAccumulate() {
        FailedAttempts limiter = limiter(3, 100);
        failTimes(limiter, "10.0.0.1", 2);
        nanos.addAndGet(WINDOW.toNanos() + 1);

        limiter.failure("10.0.0.1");

        assertThat(limiter.blocked("10.0.0.1")).isFalse();
    }

    @Test
    void successForgetsTheFailures() {
        FailedAttempts limiter = limiter(3, 100);
        failTimes(limiter, "10.0.0.1", 2);

        limiter.success("10.0.0.1");
        limiter.failure("10.0.0.1");

        assertThat(limiter.blocked("10.0.0.1")).isFalse();
        assertThat(limiter.tracked()).isEqualTo(1);
    }

    @Test
    void memoryIsBoundedWhateverTheNumberOfAddresses() {
        FailedAttempts limiter = limiter(3, 50);

        for (int i = 0; i < 5_000; i++) {
            limiter.failure("203.0.113." + i);
        }

        assertThat(limiter.tracked()).isLessThanOrEqualTo(50);
    }

    @Test
    void anAddressStillBlockedIsKeptWhileOldQuietOnesAreDropped() {
        FailedAttempts limiter = limiter(2, 3);
        failTimes(limiter, "attacker", 2);

        for (int i = 0; i < 10; i++) {
            limiter.failure("bystander-" + i);
            limiter.blocked("attacker");
        }

        assertThat(limiter.blocked("attacker")).isTrue();
    }
}
