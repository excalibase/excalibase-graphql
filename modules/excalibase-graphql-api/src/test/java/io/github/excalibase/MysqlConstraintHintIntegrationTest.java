package io.github.excalibase;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;

import java.util.Map;

import static io.github.excalibase.support.Expect.jsonPath;
import static io.github.excalibase.support.Expect.status;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/** On MySQL too, keys over the same columns are told apart by the name of their foreign key constraint. (EXC-833) */
class MysqlConstraintHintIntegrationTest extends EngineIntegrationTest {

    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withInitScript("init-mysql-constraint-hint.sql");

    private static final String REST = "/test-proj/api/v1";

    @Override
    public Map<String, String> getProperties() {
        mysql.start();
        Map<String, String> properties = EngineProperties.isolated();
        properties.put("spring.datasource.url", mysql.getJdbcUrl());
        properties.put("spring.datasource.username", mysql.getUsername());
        properties.put("spring.datasource.password", mysql.getPassword());
        properties.put("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        properties.put("app.project-id", "test-proj");
        properties.put("app.security.jwt-enabled", "false");
        properties.put("app.security.insecure-dev-mode", "true");
        properties.put("app.database-type", "mysql");
        return properties;
    }

    @Test
    void keysOverTheSameColumns_areAmbiguousWithoutAHint_andNameTheirConstraints() {
        http.get(REST + "/gchild?select=id,gparent(name)")
                .expect(status().is(300))
                .expect(jsonPath("$.code", is("ambiguous_relationship")))
                .expect(jsonPath("$.message", containsString("gparent!gchild_by_pk")))
                .expect(jsonPath("$.message", containsString("gparent!gchild_by_alt")));
    }

    @Test
    void aConstraintNameHint_picksItsOwnKey() {
        http.get(REST + "/gchild?select=id,gparent!gchild_by_pk(name)&order=id.asc")
                .expect(status().isOk())
                .expect(jsonPath("$.data[*].gparent.name", contains("one", "two")));
        http.get(REST + "/gchild?select=id,gparent!gchild_by_alt(name)&order=id.asc")
                .expect(status().isOk())
                .expect(jsonPath("$.data[*].gparent.name", contains("two", "one")));
    }
}
