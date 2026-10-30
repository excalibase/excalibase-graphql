package io.github.excalibase.observability.requests;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.ServletContext;
import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.EnumSet;

/**
 * Per-project request metrics on an internal port, only with {@code app.request-metrics.enabled}.
 * Decided at runtime rather than via {@code @ConditionalOnProperty} so it survives GraalVM AOT:
 * off, the recorder is a null bean, no filter is added to the servlet context and no port is opened.
 */
@Configuration
public class RequestMetricsConfig {

    static final String FILTER_NAME = "requestMetricsFilter";

    @Bean
    public RequestMetrics requestMetrics(@Value("${app.request-metrics.enabled:false}") boolean enabled,
                                         @Value("${app.request-metrics.max-projects:1000}") int maxProjects) {
        if (!enabled) {
            return null;
        }
        if (maxProjects < 1) {
            throw new IllegalStateException("app.request-metrics.max-projects must be at least 1");
        }
        return new RequestMetrics(maxProjects);
    }

    @Bean
    public RequestMetricsFilterInstaller requestMetricsFilterInstaller(ObjectProvider<RequestMetrics> requestMetrics,
            @Value("${app.request-metrics.port:9464}") int port) {
        return new RequestMetricsFilterInstaller(requestMetrics, port);
    }

    /**
     * The internal port, on every interface unless {@code app.request-metrics.address} names the one
     * address to bind: a single host names its platform-only network so tenant containers cannot reach it.
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> requestMetricsPort(
            ObjectProvider<RequestMetrics> requestMetrics,
            @Value("${app.request-metrics.port:9464}") int port,
            @Value("${app.request-metrics.address:}") String address) {
        return factory -> {
            if (requestMetrics.getIfAvailable() != null) {
                factory.addAdditionalTomcatConnectors(internalConnector(port, address));
            }
        };
    }

    private static Connector internalConnector(int port, String address) {
        Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        connector.setPort(port);
        String bindAddress = address.strip();
        if (!bindAddress.isEmpty() && !connector.setProperty("address", bindAddress)) {
            throw new IllegalStateException("app.request-metrics.address could not be applied: " + bindAddress);
        }
        return connector;
    }

    /**
     * Adds the filter ahead of every other one, and only when the recorder exists. A plain
     * initializer instead of a FilterRegistrationBean so nothing at all is registered when off.
     */
    public static final class RequestMetricsFilterInstaller implements ServletContextInitializer, Ordered {

        private final ObjectProvider<RequestMetrics> requestMetrics;
        private final int port;

        RequestMetricsFilterInstaller(ObjectProvider<RequestMetrics> requestMetrics, int port) {
            this.requestMetrics = requestMetrics;
            this.port = port;
        }

        @Override
        public void onStartup(ServletContext servletContext) {
            RequestMetrics metrics = requestMetrics.getIfAvailable();
            if (metrics == null) {
                return;
            }
            FilterRegistration.Dynamic registration =
                    servletContext.addFilter(FILTER_NAME, new RequestMetricsFilter(metrics, port));
            registration.setAsyncSupported(true);
            registration.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/*");
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }
}
