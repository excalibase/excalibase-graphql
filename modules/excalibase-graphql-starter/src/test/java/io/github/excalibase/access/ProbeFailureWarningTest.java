package io.github.excalibase.access;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ProbeFailureWarningTest {

    private static final String SECRET_VALUE = "alice@example.com";

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-27T00:00:00Z"));
    private final ProbeFailureWarning warning = new ProbeFailureWarning(now::get);

    @Test
    void theReason_isTheClassAndTheDatabasesCode_neverTheMessage() {
        RuntimeException failure = new IllegalStateException("probe",
                new SQLException("Duplicate entry '" + SECRET_VALUE + "' for key 'users.email'", "23000", 1062));

        String reason = ProbeFailureWarning.reason(failure);

        assertThat(reason).contains("IllegalStateException", "SQLException", "23000", "1062")
                .doesNotContain(SECRET_VALUE, "Duplicate entry");
    }

    @Test
    void aFailureWithNoDatabaseCause_isJustItsClass() {
        assertThat(ProbeFailureWarning.reason(new IllegalArgumentException(SECRET_VALUE)))
                .isEqualTo("IllegalArgumentException").doesNotContain(SECRET_VALUE);
    }

    @Test
    void afterOneReport_aSubscriptionStaysQuietForAMinute() {
        assertThat(warning.failed("orders", new RuntimeException())).isTrue();
        assertThat(warning.failed("orders", new RuntimeException())).isFalse();

        now.set(now.get().plus(Duration.ofSeconds(59)));
        assertThat(warning.failed("orders", new RuntimeException())).isFalse();

        now.set(now.get().plus(Duration.ofSeconds(2)));
        assertThat(warning.failed("orders", new RuntimeException())).isTrue();
    }
}
