package io.github.excalibase;

import io.github.excalibase.support.EngineProperties;
import java.util.Map;
import org.testcontainers.containers.PostgreSQLContainer;

/** {@link RestFilterSemanticsContract} on Postgres. */
class PostgresRestFilterSemanticsIntegrationTest extends RestFilterSemanticsContract {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-rest-semantics.sql");

    @Override
    public Map<String, String> getProperties() {
        return EngineProperties.postgres(postgres, "test-proj");
    }

    @Override
    String goodsField() {
        return "publicGoods";
    }
}
