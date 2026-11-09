package io.github.excalibase.overload;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** Every limit is a share of the container (heap percent, requests per CPU), so one image fits any size. */
@Configuration
@ConditionalOnProperty(name = "app.overload.enabled", havingValue = "true", matchIfMissing = true)
public class OverloadConfig {

    /** Runs ahead of the security chain (-100) so a shed request costs almost nothing. */
    private static final int BEFORE_SECURITY = -200;

    @Bean
    public FilterRegistrationBean<OverloadShedFilter> overloadShedFilter(
            @Value("${app.overload.heap-threshold-percent:85}") int heapThresholdPercent,
            @Value("${app.overload.max-in-flight-per-cpu:16}") int maxInFlightPerCpu,
            @Value("${app.overload.queue-wait-ms:500}") long queueWaitMillis,
            @Value("${app.overload.retry-after-seconds:1}") int retryAfterSeconds) {
        InFlightLimiter limiter = InFlightLimiter.forCpus(Runtime.getRuntime().availableProcessors(),
                maxInFlightPerCpu, Duration.ofMillis(queueWaitMillis));
        OverloadShedFilter filter = new OverloadShedFilter(limiter,
                new RetainedHeapPressure(heapThresholdPercent), retryAfterSeconds);
        FilterRegistrationBean<OverloadShedFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(BEFORE_SECURITY);
        return registration;
    }
}
