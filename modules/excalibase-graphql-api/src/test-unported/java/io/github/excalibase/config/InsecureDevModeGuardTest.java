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
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Authentication is on unless an operator explicitly opts into insecure dev
 * mode; with it off the process refuses to start rather than serve every row.
 */
class InsecureDevModeGuardTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(InsecureDevModeGuard.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("authentication off without the insecure dev flag refuses to start")
    void authOffWithoutFlag_refuses() {
        InsecureDevModeGuard guard = new InsecureDevModeGuard(false, "");

        assertThatThrownBy(guard::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.security.insecure-dev-mode");
    }

    @Test
    @DisplayName("authentication off with the insecure dev flag starts and logs at ERROR")
    void authOffWithFlag_logsLoudly() {
        new InsecureDevModeGuard(true, "").check();

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage()).contains("INSECURE DEV MODE");
    }

    @Test
    @DisplayName("insecure dev mode with RLS policies configured names the unenforced policy source")
    void authOffWithPolicies_namesThePolicySource() {
        new InsecureDevModeGuard(true, "http://provisioning/api").check();

        assertThat(appender.list.getFirst().getFormattedMessage())
                .contains("Row-Level Security is NOT enforced")
                .contains("http://provisioning/api");
    }

    @Test
    @DisplayName("with no property set authentication is on and the guard is not involved")
    void noProperty_guardInactive() {
        new ApplicationContextRunner()
                .withUserConfiguration(InsecureDevModeGuard.class)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(InsecureDevModeGuard.class));
    }

    @Test
    @DisplayName("jwt-enabled=false alone fails the context")
    void authOffProperty_failsContext() {
        new ApplicationContextRunner()
                .withUserConfiguration(InsecureDevModeGuard.class)
                .withPropertyValues("app.security.jwt-enabled=false")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("jwt-enabled=false with the insecure dev flag starts")
    void authOffPropertyWithFlag_starts() {
        new ApplicationContextRunner()
                .withUserConfiguration(InsecureDevModeGuard.class)
                .withPropertyValues("app.security.jwt-enabled=false", "app.security.insecure-dev-mode=true")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(InsecureDevModeGuard.class));
    }
}
