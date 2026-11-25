package io.github.excalibase;

import io.github.excalibase.support.EngineProperties;
import java.util.Map;
import org.testcontainers.containers.MySQLContainer;

class MysqlConnectionPagingIntegrationTest extends ConnectionPagingContract {

    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withInitScript("init-connection-paging.sql");

    @Override
    public Map<String, String> getProperties() {
        return EngineProperties.mysql(mysql, "test-proj");
    }

    @Override
    String connection() {
        return "testProductsConnection";
    }
}
