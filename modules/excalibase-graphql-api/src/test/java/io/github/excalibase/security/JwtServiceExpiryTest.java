package io.github.excalibase.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceExpiryTest {

    private static final Instant EXPIRY = Instant.parse("2099-01-01T00:00:00Z");

    private static JwtClaims withExp(Object exp) {
        Map<String, Object> extra = exp == null ? Map.of() : Map.of("exp", exp);
        return new JwtClaims("u", "p1", "acme", "demo", "", "authenticated", "u@x.com", "", 0L, extra, null);
    }

    @Test
    @DisplayName("a Date exp, as the JWT library returns it, is read")
    void date() {
        assertThat(JwtService.expiresAt(withExp(Date.from(EXPIRY)))).isEqualTo(EXPIRY);
    }

    @Test
    @DisplayName("an Instant exp is read")
    void instant() {
        assertThat(JwtService.expiresAt(withExp(EXPIRY))).isEqualTo(EXPIRY);
    }

    @Test
    @DisplayName("a numeric exp is epoch seconds")
    void number() {
        assertThat(JwtService.expiresAt(withExp(EXPIRY.getEpochSecond()))).isEqualTo(EXPIRY);
    }

    @Test
    @DisplayName("no exp, or one of another type, means no expiry")
    void none() {
        assertThat(JwtService.expiresAt(withExp(null))).isNull();
        assertThat(JwtService.expiresAt(withExp("tomorrow"))).isNull();
    }
}
