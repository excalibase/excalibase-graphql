package io.github.excalibase.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${app.cors.allowed-origins:*}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // Public, browser-facing API (the SDK runs in browser JS), so CORS must
        // cover every surface — GraphQL + REST, scoped + legacy. Auth is a Bearer
        // token in the Authorization header (no cookies), so credentials are off
        // and a wildcard origin is appropriate; tighten via app.cors.allowed-origins.
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins.split(","))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Content-Range", "Location")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
