package io.github.excalibase;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** {@link RestFilterSemanticsContract} on MySQL. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MysqlRestFilterSemanticsIntegrationTest extends RestFilterSemanticsContract {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4").withInitScript("init-mysql-rest-semantics.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("app.project-id", () -> "test-proj");
        registry.add("app.security.jwt-enabled", () -> "false");
        registry.add("app.security.insecure-dev-mode", () -> "true");
        registry.add("app.database-type", () -> "mysql");
        registry.add("app.max-rows", () -> 30);
    }

    @Override
    String goodsField() {
        return "testGoods";
    }
}
