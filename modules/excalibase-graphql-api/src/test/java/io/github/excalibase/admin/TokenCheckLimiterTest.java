package io.github.excalibase.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The token checker has its own limit: counting every check, right or wrong, and never reset by a correct secret. */
class TokenCheckLimiterTest {

    private static final String ADDRESS = "10.0.0.1";
    private final AtomicLong nanos = new AtomicLong();

    private TokenCheckLimiter limiter(int max) {
        return new TokenCheckLimiter(max, Duration.ofSeconds(60), 100, nanos::get);
    }

    @Test
    @DisplayName("the checks after the limit are refused, with the time to wait")
    void blocksAfterTheLimit() {
        TokenCheckLimiter limiter = limiter(3);

        assertThat(limiter.tryAcquire(ADDRESS)).isTrue();
        assertThat(limiter.tryAcquire(ADDRESS)).isTrue();
        assertThat(limiter.tryAcquire(ADDRESS)).isTrue();
        assertThat(limiter.tryAcquire(ADDRESS)).isFalse();
        assertThat(limiter.retryAfterSeconds(ADDRESS)).isBetween(1L, 60L);
    }

    @Test
    @DisplayName("another address has its own count")
    void perAddress() {
        TokenCheckLimiter limiter = limiter(1);

        assertThat(limiter.tryAcquire(ADDRESS)).isTrue();
        assertThat(limiter.tryAcquire("10.0.0.2")).isTrue();
        assertThat(limiter.tryAcquire(ADDRESS)).isFalse();
    }

    @Test
    @DisplayName("a new window starts from zero")
    void windowEnds() {
        TokenCheckLimiter limiter = limiter(1);
        limiter.tryAcquire(ADDRESS);
        assertThat(limiter.tryAcquire(ADDRESS)).isFalse();

        nanos.addAndGet(Duration.ofSeconds(61).toNanos());

        assertThat(limiter.tryAcquire(ADDRESS)).isTrue();
    }

    @Test
    @DisplayName("a refused check does not extend the window")
    void refusalsDoNotExtend() {
        TokenCheckLimiter limiter = limiter(1);
        limiter.tryAcquire(ADDRESS);
        nanos.addAndGet(Duration.ofSeconds(59).toNanos());
        assertThat(limiter.tryAcquire(ADDRESS)).isFalse();
        nanos.addAndGet(Duration.ofSeconds(2).toNanos());

        assertThat(limiter.tryAcquire(ADDRESS)).isTrue();
    }

    @Test
    @DisplayName("only a bounded number of addresses are tracked")
    void bounded() {
        TokenCheckLimiter limiter = new TokenCheckLimiter(5, Duration.ofSeconds(60), 2, nanos::get);

        limiter.tryAcquire("a");
        limiter.tryAcquire("b");
        limiter.tryAcquire("c");

        assertThat(limiter.tracked()).isEqualTo(2);
    }

    @Test
    @DisplayName("a limit or a window that is not positive is refused")
    void invalid() {
        Duration second = Duration.ofSeconds(1);
        Duration none = Duration.ZERO;

        assertThatThrownBy(() -> new TokenCheckLimiter(0, second, 1, nanos::get)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenCheckLimiter(1, none, 1, nanos::get)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenCheckLimiter(1, second, 0, nanos::get)).isInstanceOf(IllegalArgumentException.class);
    }
}
