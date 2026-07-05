package io.github.excalibase.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit M8: verifies the loud startup warning fired when JWT (and therefore RLS)
 * is disabled — escalated to error level when RLS policies are actually configured.
 */
class RlsEnforcementWarningTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(RlsEnforcementWarning.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("no policy URL: a WARN notes RLS is disabled")
    void noPolicies_warns() {
        new RlsEnforcementWarning("").warnRlsDisabled();

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).contains("Row-Level Security are disabled");
    }

    @Test
    @DisplayName("policy URL configured but JWT off: escalates to ERROR")
    void policiesConfigured_errors() {
        new RlsEnforcementWarning("http://provisioning/rls-policies/").warnRlsDisabled();

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage())
                .contains("Row-Level Security is NOT enforced")
                .contains("http://provisioning/rls-policies/");
    }
}
