package io.github.excalibase;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** With strict select names on, a name outside the allowlist is a 400 before any SQL is built. */
class RestStrictSelectNamesMysqlE2ETest extends EngineIntegrationTest {
    static final org.testcontainers.containers.MySQLContainer<?> mysql =
            new org.testcontainers.containers.MySQLContainer<>("mysql:8.4").withInitScript("rest-embed-columns-mysql.sql");

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = EngineProperties.mysql(mysql, "test-proj");
        properties.put("app.security.jwt-enabled", "false");
        properties.put("app.security.insecure-dev-mode", "true");
        properties.put("app.rest.strict-select-names", "true");
        return properties;
    }

    private HttpAnswer embed(String column) {
        String select = URLEncoder.encode("id,docs!created_by(" + column + ",users!updated_by(id))", StandardCharsets.UTF_8);
        return http.get("/test-proj/api/v1/users?select=" + select, "Accept-Profile", "test");
    }

    @Test
    void anOddNameIsRefused() {
        for (String column : List.of("x\\", "a'b", "\\'||sleep(2)||'", "a b")) {
            assertEquals(400, embed("title," + column).status(), column);
        }
    }

    @Test
    void anEmbedNamingOnlyUnknownColumnsIsRefused() {
        assertEquals(400, embed("nope").status());
    }

    @Test
    void aQuotedNameReads() {
        assertEquals(200, embed("\"title\"").status());
    }

    @Test
    void aRealColumnStillReads() {
        HttpAnswer answer = embed("title");
        assertEquals(200, answer.status(), answer.body());
    }
}
