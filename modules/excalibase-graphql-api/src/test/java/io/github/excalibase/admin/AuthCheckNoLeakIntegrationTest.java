package io.github.excalibase.admin;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.nimbusds.jose.JWSAlgorithm;
import io.github.excalibase.support.EngineClient;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.FreePort;
import io.github.excalibase.support.HttpAnswer;
import io.micronaut.context.ApplicationContext;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static io.github.excalibase.admin.AdminTestEngine.DOCUMENT;
import static io.github.excalibase.admin.AdminTestEngine.HEADER;
import static io.github.excalibase.admin.AdminTestEngine.SECRET;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Test a token" takes the token of a real user. Whatever happens to it (good, expired, garbage, too big, a refused
 * body), the token and its claims appear in no log line, no exception, no metric tag and no answer.
 */
class AuthCheckNoLeakIntegrationTest extends EngineIntegrationTest {

    private static final int ADMIN_PORT = FreePort.find();
    private static final Path FILE = AdminTestEngine.documentFile(DOCUMENT);
    private static final String TEXT = "text/plain";
    private static final String CANARY = "canary-7f3a91-claim-fragment";

    @Inject
    ApplicationContext context;

    private final EngineClient admin = AdminTestEngine.on(ADMIN_PORT);

    @Override
    public Map<String, String> getProperties() {
        return AdminTestEngine.authPageOn(FILE, -1, ADMIN_PORT);
    }

    private HttpAnswer check(String body, String contentType) {
        return admin.post("/_admin/v1/auth/check", body, HEADER, SECRET, "Content-Type", contentType);
    }

    private static String allText(ListAppender<ILoggingEvent> logs) {
        List<String> lines = new ArrayList<>();
        for (ILoggingEvent event : new ArrayList<>(logs.list)) {
            lines.add(event.getFormattedMessage());
            for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                lines.add(proxy.getMessage());
            }
            lines.add(String.valueOf(event.getMDCPropertyMap()));
            lines.add(String.valueOf(event.getKeyValuePairs()));
        }
        return String.join("\n", lines);
    }

    @Test
    @DisplayName("the canary token is in no log, no exception, no metric tag and no answer, for any outcome")
    void canaryNeverAppears() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level before = root.getLevel();
        root.setLevel(Level.TRACE);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
        String good = AuthTokens.sign(claims -> claims.claim("note", CANARY));
        String expired = AuthTokens.sign(claims -> claims.claim("note", CANARY).expirationTime(new Date(1000)));
        String wrongKey = AuthTokens.sign(AuthTokens.good().claim("note", CANARY), JWSAlgorithm.HS256,
                AuthTokens.OTHER_SECRET);
        List<HttpAnswer> answers = new ArrayList<>();
        try {
            answers.add(check(good, TEXT));
            answers.add(check(expired, TEXT));
            answers.add(check(wrongKey, TEXT));
            answers.add(check(CANARY, TEXT));
            answers.add(check("{\"token\":\"" + CANARY, "application/json"));
            answers.add(check(CANARY.repeat(1000), TEXT));
            answers.add(check(CANARY, "application/x-www-form-urlencoded"));
            answers.add(check(CANARY, "multipart/form-data; boundary=x"));
        } finally {
            root.detachAppender(logs);
            root.setLevel(before);
        }

        assertThat(allText(logs)).doesNotContain(CANARY).doesNotContain(good).doesNotContain(expired);
        MeterRegistry meters = context.getBean(MeterRegistry.class);
        meters.getMeters().forEach(meter -> assertThat(meter.getId().toString()).doesNotContain(CANARY));
        for (HttpAnswer answer : answers) {
            assertThat(answer.body()).doesNotContain(CANARY);
        }
        assertThat(answers.get(1).status()).isEqualTo(200);
    }
}
