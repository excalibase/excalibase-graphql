package io.github.excalibase.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * Registers {@link HttpRequestTimerFilter} where the observation filter used to sit, so it times
 * the whole chain including authentication failures. Registered only at the {@code lite} level, so
 * {@code full} and {@code off} have the same filter chain as before. The decision is read at runtime, not by
 * a bean condition, so it also holds in a native image.
 */
@Configuration
public class HttpTimerFilterConfig {

    @Bean
    public FilterRegistrationBean<HttpRequestTimerFilter> httpRequestTimerFilter(MeterRegistry registry,
                                                                                 Environment environment) {
        boolean active = ObservabilityLevelEnvironmentPostProcessor.levelOf(environment) == ObservabilityLevel.LITE;
        FilterRegistrationBean<HttpRequestTimerFilter> registration =
                new FilterRegistrationBean<>(new HttpRequestTimerFilter(registry, active));
        registration.setEnabled(active);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }
}
