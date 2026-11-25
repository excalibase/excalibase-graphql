package io.github.excalibase;

import io.github.excalibase.support.EngineProperties;
import java.util.Map;
import org.testcontainers.containers.MySQLContainer;

/** {@link RestFilterSemanticsContract} on MySQL. */
class MysqlRestFilterSemanticsIntegrationTest extends RestFilterSemanticsContract {

    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4").withInitScript("init-mysql-rest-semantics.sql");

    @Override
    public Map<String, String> getProperties() {
        return EngineProperties.mysql(mysql, "test-proj");
    }

    @Override
    String goodsField() {
        return "testGoods";
    }
}
