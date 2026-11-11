package io.github.excalibase.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.web.servlet.handler.HandlerMappingIntrospector;

/**
 * Registers {@link HttpRequestTimerFilter} where the observation filter used to sit, so it times
 * the whole chain including authentication failures. Registered only at the {@code lite} level and only
 * when the HTTP observation is really off, asked of the observation registry itself rather than of a
 * property, so the metric is never recorded twice. {@code full} and {@code off} keep the filter chain of
 * before. The decision is read at runtime, not by a bean condition, so it also holds in a native image.
 */
@Configuration
public class HttpTimerFilterConfig {

    private static final String HTTP_OBSERVATION = "http.server.requests";

    /**
     * At {@code lite}, makes the security filter chain leave a firewall rejection on the request, as the
     * Spring Security observation did. Other levels keep the chain exactly as Spring Security builds it.
     */
    @Bean
    public static BeanPostProcessor rejectedRequestRecorderInstaller(Environment environment,
            ObjectProvider<ObservationRegistry> observations) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof FilterChainProxy chain && timerActive(environment, observations.getObject())) {
                    chain.setRequestRejectedHandler(new RejectedRequestRecorder());
                }
                return bean;
            }
        };
    }

    private static boolean timerActive(Environment environment, ObservationRegistry observations) {
        return ObservabilityLevelEnvironmentPostProcessor.levelOf(environment) == ObservabilityLevel.LITE
                && Observation.createNotStarted(HTTP_OBSERVATION, observations).isNoop();
    }

    @Bean
    public FilterRegistrationBean<HttpRequestTimerFilter> httpRequestTimerFilter(MeterRegistry registry,
            ObservationRegistry observations, Environment environment,
            ObjectProvider<HandlerMappingIntrospector> introspector) {
        boolean active = timerActive(environment, observations);
        RoutePatternResolver routes = introspector.getIfAvailable() == null
                ? RoutePatternResolver.NONE : RoutePatternResolver.of(introspector.getIfAvailable());
        FilterRegistrationBean<HttpRequestTimerFilter> registration =
                new FilterRegistrationBean<>(new HttpRequestTimerFilter(registry, active, routes));
        registration.setEnabled(active);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }
}
