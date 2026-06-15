package io.github.excalibase.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /**
     * Allowed cross-origin origins for {@code /graphql}. Defaults to empty —
     * cross-origin requests are denied unless an operator sets
     * {@code APP_CORS_ALLOWED_ORIGINS} (e.g. {@code https://app.example.com}).
     * A wildcard ({@code *}) must be opted into explicitly; it is no longer the
     * default, so a fresh deploy is not open to every origin.
     */
    @Value("${app.cors.allowed-origins:}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = parseOrigins(allowedOrigins);
        if (origins.length == 0) {
            // No origins configured: register no cross-origin mapping at all, so
            // the browser's default same-origin policy applies (deny by default).
            return;
        }
        registry.addMapping("/graphql")
                .allowedOrigins(origins)
                .allowedMethods("POST", "GET", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false);
    }

    /** Splits the comma-separated origins, dropping blank entries. */
    private static String[] parseOrigins(String raw) {
        if (raw == null || raw.isBlank()) {
            return new String[0];
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toArray(String[]::new);
    }
}
