package io.github.excalibase;

import io.github.excalibase.support.EngineProperties;
import java.util.Map;
import org.testcontainers.containers.PostgreSQLContainer;

class PostgresConnectionPagingIntegrationTest extends ConnectionPagingContract {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-connection-paging.sql");

    @Override
    public Map<String, String> getProperties() {
        return EngineProperties.postgres(postgres, "test-proj");
    }

    @Override
    String connection() {
        return "publicProductsConnection";
    }
}
