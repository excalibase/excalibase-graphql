package io.github.excalibase;

import static org.assertj.core.api.Assertions.assertThat;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.ServerFilter;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Beans are found when the engine is compiled, not by scanning at start. A component the annotation processor
 * skipped would silently never be created, so every unconditional component class of the engine must be a bean.
 */
class ComponentScanCoverageTest {

    @Test
    @DisplayName("every unconditional component of the application is a bean of the running application")
    void everyComponentOfTheApplicationIsABean() throws Exception {
        Map<String, Object> properties = new HashMap<>(Map.of(
                "spring.datasource.url", "jdbc:postgresql://localhost:59999/nonexistent",
                "spring.datasource.username", "fake",
                "spring.datasource.password", "fake",
                "app.project-id", "test-proj",
                "app.nats.enabled", "false",
                "app.security.auth.hmac-secret", "test-hmac-secret-0123456789abcdef0123456789",
                "micronaut.server.port", "-1",
                "endpoints.all.port", "-1"));
        List<Class<?>> components = componentClasses();
        assertThat(components).as("component classes found under the engine's classes").isNotEmpty();

        try (ApplicationContext context = ApplicationContext.run(properties)) {
            List<String> missing = components.stream()
                    .filter(type -> !context.containsBean(type))
                    .map(Class::getName).sorted().toList();

            assertThat(missing).as("components that are not beans").isEmpty();
        }
    }

    private static List<Class<?>> componentClasses() throws IOException, URISyntaxException {
        Path root = Path.of(ExcalibaseGraphqlApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".class") && !path.toString().contains("$"))
                    .map(path -> root.relativize(path).toString().replace('/', '.').replaceAll("\\.class$", ""))
                    .map(ComponentScanCoverageTest::load)
                    .filter(type -> type != null && isComponent(type) && !type.isInterface()
                            && !type.isAnnotationPresent(Requires.class))
                    .toList();
        }
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name, false, ComponentScanCoverageTest.class.getClassLoader());
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return null;
        }
    }

    private static boolean isComponent(Class<?> type) {
        return type.isAnnotationPresent(Singleton.class) || type.isAnnotationPresent(Controller.class)
                || type.isAnnotationPresent(ServerFilter.class) || type.isAnnotationPresent(Context.class)
                || type.isAnnotationPresent(Factory.class);
    }
}
