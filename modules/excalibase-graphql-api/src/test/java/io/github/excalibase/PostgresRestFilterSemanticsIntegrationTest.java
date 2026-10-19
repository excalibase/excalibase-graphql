package io.github.excalibase;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** {@link RestFilterSemanticsContract} on Postgres. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PostgresRestFilterSemanticsIntegrationTest extends RestFilterSemanticsContract {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-rest-semantics.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.project-id", () -> "test-proj");
        registry.add("app.security.jwt-enabled", () -> "false");
        registry.add("app.security.insecure-dev-mode", () -> "true");
        registry.add("app.database-type", () -> "postgres");
        registry.add("app.max-rows", () -> 30);
    }

    @Override
    String goodsField() {
        return "publicGoods";
    }
}
