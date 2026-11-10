package io.github.excalibase.overload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Every limit is derived from the CPUs the container has, so one image fits any size. */
@Configuration
@ConditionalOnProperty(name = "app.overload.enabled", havingValue = "true", matchIfMissing = true)
public class OverloadConfig {

    private static final Logger log = LoggerFactory.getLogger(OverloadConfig.class);

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatConnectionBound(
            @Value("${app.overload.max-connections-per-cpu:256}") int connectionsPerCpu,
            @Value("${app.overload.accept-count-per-cpu:256}") int backlogPerCpu,
            @Value("${app.overload.max-keep-alive-requests:20}") int maxKeepAliveRequests,
            @Value("${app.overload.keep-alive-timeout-ms:5000}") int keepAliveTimeoutMillis) {
        int cpus = Runtime.getRuntime().availableProcessors();
        TomcatConnectionBound bound = new TomcatConnectionBound(cpus, connectionsPerCpu, backlogPerCpu,
                maxKeepAliveRequests, keepAliveTimeoutMillis);
        log.info("Connection bound on the main port: cpus={}, {}", cpus, bound);
        return new AfterDefaults(factory -> factory.addConnectorCustomizers(bound::apply));
    }

    /** Runs after Spring's own server.tomcat.* customizer so these limits win. */
    private record AfterDefaults(WebServerFactoryCustomizer<TomcatServletWebServerFactory> delegate)
            implements WebServerFactoryCustomizer<TomcatServletWebServerFactory>, Ordered {

        @Override
        public void customize(TomcatServletWebServerFactory factory) {
            delegate.customize(factory);
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }
}
