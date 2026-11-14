package io.github.excalibase.config;

import io.github.excalibase.http.RequestBodyLimitFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * The request body cap (EXC-726) as a plain servlet filter, one step after Spring Security's chain: the chain has
 * already set the security headers and the CORS vary headers the refusal carries, and the filter adds no step to
 * the chain, so the security metrics keep their series.
 */
@Configuration
public class RequestBodyLimitConfig {

    @Bean
    public FilterRegistrationBean<RequestBodyLimitFilter> requestBodyLimitFilter(
            @Value("${app.max-request-body:8MB}") DataSize maxBody) {
        FilterRegistrationBean<RequestBodyLimitFilter> registration =
                new FilterRegistrationBean<>(new RequestBodyLimitFilter(maxBody.toBytes()));
        registration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER + 1);
        return registration;
    }
}
