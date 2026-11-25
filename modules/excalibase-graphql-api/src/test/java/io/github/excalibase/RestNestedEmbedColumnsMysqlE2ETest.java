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
import static org.junit.jupiter.api.Assertions.assertFalse;

/** A nested embed's columns are checked against its table on MySQL, whatever characters they carry. */
class RestNestedEmbedColumnsMysqlE2ETest extends EngineIntegrationTest {
    static final org.testcontainers.containers.MySQLContainer<?> mysql =
            new org.testcontainers.containers.MySQLContainer<>("mysql:8.4").withInitScript("rest-embed-columns-mysql.sql");

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = EngineProperties.mysql(mysql, "test-proj");
        properties.put("app.security.jwt-enabled", "false");
        properties.put("app.security.insecure-dev-mode", "true");
        return properties;
    }

    private HttpAnswer embed(String column) {
        String select = URLEncoder.encode("id,docs!created_by(" + column + ",users!updated_by(id))", StandardCharsets.UTF_8);
        return http.get("/test-proj/api/v1/users?select=" + select, "Accept-Profile", "test");
    }

    @Test
    void aColumnTheTableLacksIsNeverASyntaxError() {
        for (String column : List.of("x\\", "a'b", "\\'||sleep(2)||'", "\\' or sleep(2) or '")) {
            HttpAnswer answer = embed("title," + column);
            assertEquals(200, answer.status(), column);
            assertFalse(answer.status() >= 500, column + " => " + answer.status());
            assertFalse(answer.body().toLowerCase().contains("syntax"), column + " => " + answer.body());
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
