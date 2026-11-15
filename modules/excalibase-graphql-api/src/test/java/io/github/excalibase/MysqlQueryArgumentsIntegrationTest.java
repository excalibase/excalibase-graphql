package io.github.excalibase;

import io.github.excalibase.support.EngineProperties;
import java.util.Map;
import org.testcontainers.containers.MySQLContainer;

class MysqlQueryArgumentsIntegrationTest extends QueryArgumentsContract {

    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withInitScript("init-query-arguments.sql");

    @Override
    public Map<String, String> getProperties() {
        return EngineProperties.mysql(mysql, "test-proj");
    }

    @Override
    String field() {
        return "testProducts";
    }
}
