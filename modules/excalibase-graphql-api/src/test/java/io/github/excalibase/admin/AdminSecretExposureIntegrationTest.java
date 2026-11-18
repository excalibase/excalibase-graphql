package io.github.excalibase.admin;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import io.github.excalibase.support.EngineClient;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.FreePort;
import io.github.excalibase.support.HttpAnswer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static io.github.excalibase.admin.AdminTestEngine.HEADER;
import static io.github.excalibase.admin.AdminTestEngine.PROJECT;
import static io.github.excalibase.admin.AdminTestEngine.SECRET;
import static org.assertj.core.api.Assertions.assertThat;

/** Where the admin secret must never be: the logs, any answer, the public port's behaviour. */
class AdminSecretExposureIntegrationTest extends EngineIntegrationTest {

    private static final int ADMIN_PORT = FreePort.find();
    private static final String ATTEMPT = "an-attempted-secret-that-is-wrong-0123456789";
    private static final String GRAPHQL = "/" + PROJECT + "/graphql";
    private static final String PING = "{ __typename }";
    private static final String ROLE = "X-Excalibase-Role";
    private static final String ADMIN_METADATA = "/_admin/v1/metadata";

    private final EngineClient admin = AdminTestEngine.on(ADMIN_PORT);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = AdminTestEngine.consoleOn(
                AdminTestEngine.documentFile(AdminTestEngine.DOCUMENT), -1, ADMIN_PORT);
        properties.put("excalibase.admin.max-failures", "1000");
        return properties;
    }

    @BeforeEach
    void capture() {
        logs.start();
        root.addAppender(logs);
        root.setLevel(Level.TRACE);
    }

    @AfterEach
    void release() {
        root.detachAppender(logs);
        logs.stop();
    }

    private String everythingLogged() {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : List.copyOf(logs.list)) {
            text.append(event.getFormattedMessage()).append('\n').append(event.getMDCPropertyMap()).append('\n');
            if (event.getThrowableProxy() != null) {
                text.append(ThrowableProxyUtil.asString(event.getThrowableProxy()));
            }
        }
        return text.toString();
    }

    @Test
    @DisplayName("the secret, and what a caller sent as one, is never logged, on success or failure")
    void neverInTheLogs() {
        admin.get(ADMIN_METADATA, HEADER, SECRET);
        admin.get(ADMIN_METADATA, HEADER, ATTEMPT);
        admin.get(ADMIN_METADATA);
        admin.post("/_admin/v1/validate", "garbage: [", HEADER, SECRET, "Content-Type", "text/plain");
        admin.get("/_admin/v1/schema", HEADER, SECRET);
        http.get(ADMIN_METADATA, HEADER, SECRET);
        http.graphql(GRAPHQL, PING, HEADER, SECRET);

        String logged = everythingLogged();

        assertThat(logged).doesNotContain(SECRET).doesNotContain(ATTEMPT);
    }

    @Test
    @DisplayName("no answer from the admin API contains the secret")
    void neverInAnAnswer() {
        for (String path : List.of(ADMIN_METADATA, "/_admin/v1/replicas", "/_admin/v1/schema")) {
            HttpAnswer answer = admin.get(path, HEADER, SECRET);
            assertThat(answer.body()).doesNotContain(SECRET);
            assertThat(answer.headers().map().toString()).doesNotContain(SECRET);
        }
        assertThat(admin.get(ADMIN_METADATA, HEADER, ATTEMPT).body()).doesNotContain(ATTEMPT);
    }

    @Test
    @DisplayName("the public port gives the same answer with the admin secret as without it")
    void publicPortIgnoresTheHeader() {
        HttpAnswer without = http.graphql(GRAPHQL, PING);
        HttpAnswer with = http.graphql(GRAPHQL, PING, HEADER, SECRET);

        assertThat(with.status()).isEqualTo(without.status());
        assertThat(with.body()).isEqualTo(without.body());
    }

    @Test
    @DisplayName("the admin secret never makes a public request the service role")
    void notTheServiceRole() {
        HttpAnswer plain = http.graphql(GRAPHQL, PING, ROLE, "service");
        HttpAnswer withSecret = http.graphql(GRAPHQL, PING, ROLE, "service", HEADER, SECRET);
        HttpAnswer asBearer = http.graphql(GRAPHQL, PING, "Authorization", "Bearer " + SECRET);

        assertThat(plain.status()).isEqualTo(403);
        assertThat(withSecret.status()).isEqualTo(plain.status());
        assertThat(withSecret.body()).isEqualTo(plain.body());
        assertThat(asBearer.status()).isEqualTo(401);
    }
}
