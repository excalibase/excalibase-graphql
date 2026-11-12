package io.github.excalibase;

import io.github.excalibase.support.EngineProperties;
import java.util.Map;
import org.testcontainers.containers.MySQLContainer;

/** {@link ResponseShapeContract} on MySQL. */
class MysqlResponseShapeIntegrationTest extends ResponseShapeContract {

    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withInitScript("init-mysql-shape-contract.sql");

    @Override
    public Map<String, String> getProperties() {
        return EngineProperties.mysql(mysql, "test-proj");
    }

    @Override
    String itemsField() {
        return "testItems";
    }
}
