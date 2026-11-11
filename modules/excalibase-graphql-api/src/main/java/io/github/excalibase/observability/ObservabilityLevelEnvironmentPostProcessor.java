package io.github.excalibase.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;

/**
 * Applies {@link ObservabilityLevel} by switching observations off through
 * {@code management.observations.enable.*}, which Spring reads at runtime. Added as a lowest-priority
 * default, so an explicit setting still wins, and {@code full} adds nothing at all. An unknown level
 * stops startup here.
 */
public class ObservabilityLevelEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String PREFIX = "management.observations.enable.";
    private static final String SOURCE = "excalibaseObservabilityLevel";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> disabled = new LinkedHashMap<>();
        switch (levelOf(environment)) {
            case FULL -> { }
            case LITE -> disable(disabled, "http", "spring.security");
            case OFF -> disable(disabled, "http", "spring.security", "jdbc", "tomcat");
        }
        if (!disabled.isEmpty()) {
            environment.getPropertySources().addLast(new MapPropertySource(SOURCE, disabled));
        }
    }

    /** The level the environment asks for; the timer filter records the metric only on {@code lite}. */
    static ObservabilityLevel levelOf(Environment environment) {
        return ObservabilityLevel.parse(environment.getProperty(ObservabilityLevel.PROPERTY));
    }

    private static void disable(Map<String, Object> properties, String... observations) {
        for (String observation : observations) {
            properties.put(PREFIX + observation, "false");
        }
    }
}
