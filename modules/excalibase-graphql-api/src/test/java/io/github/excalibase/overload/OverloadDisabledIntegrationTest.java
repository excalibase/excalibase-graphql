package io.github.excalibase.overload;

import io.github.excalibase.ExcalibaseGraphqlApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = ExcalibaseGraphqlApplication.class)
class OverloadDisabledIntegrationTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:59999/nonexistent");
        registry.add("spring.datasource.username", () -> "fake");
        registry.add("spring.datasource.password", () -> "fake");
        registry.add("app.project-id", () -> "test-proj");
        registry.add("app.security.jwt-enabled", () -> "false");
        registry.add("app.security.insecure-dev-mode", () -> "true");
        registry.add("app.database-type", () -> "postgres");
        registry.add("app.overload.enabled", () -> "false");
    }

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("with app.overload.enabled=false nothing is registered")
    void nothingRegistered() {
        assertThat(context.containsBean("tomcatConnectionBound")).isFalse();
    }
}
