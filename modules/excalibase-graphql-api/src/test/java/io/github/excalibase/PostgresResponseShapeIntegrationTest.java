package io.github.excalibase;

import io.github.excalibase.support.EngineProperties;
import java.util.Map;
import org.testcontainers.containers.PostgreSQLContainer;

/** {@link ResponseShapeContract} on Postgres. */
class PostgresResponseShapeIntegrationTest extends ResponseShapeContract {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-shape-contract.sql");


    @Override
    public Map<String, String> getProperties() {
        return EngineProperties.postgres(postgres, "test-proj");
    }

    @Override
    String itemsField() {
        return "publicItems";
    }
}
