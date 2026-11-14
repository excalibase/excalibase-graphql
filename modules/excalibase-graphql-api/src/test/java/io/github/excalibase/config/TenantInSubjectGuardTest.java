package io.github.excalibase.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantInSubjectGuardTest {

    @Test
    @DisplayName("multi-tenant realtime with tenant-in-subject off refuses to start")
    void refusesWithoutTenantInSubject() {
        assertThatThrownBy(() -> new TenantInSubjectGuard(true, "http://p", true, false, false).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.nats.tenant-in-subject=true");
    }

    @Test
    @DisplayName("tenant-in-subject on, NATS off, or the explicit local opt-out all start")
    void startsWhenSafeOrOptedOut() {
        assertThatCode(() -> new TenantInSubjectGuard(true, "http://p", true, true, false).check()).doesNotThrowAnyException();
        assertThatCode(() -> new TenantInSubjectGuard(true, "http://p", false, false, false).check()).doesNotThrowAnyException();
        assertThatCode(() -> new TenantInSubjectGuard(true, "http://p", true, false, true).check()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a single-tenant engine, or one with authentication off, is not affected")
    void singleTenantIsNotAffected() {
        assertThatCode(() -> new TenantInSubjectGuard(true, "", true, false, false).check())
                .doesNotThrowAnyException();
        assertThatCode(() -> new TenantInSubjectGuard(false, "http://p", true, false, false).check())
                .doesNotThrowAnyException();
    }
}
