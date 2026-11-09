package io.github.excalibase.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code app.graphql.parse-cache.heap-share}: the share of the maximum heap the parsed-query cache may use. */
@ConfigurationProperties(prefix = "app.graphql.parse-cache")
public record ParseCacheProperties(@DefaultValue("0.02") double heapShare) {

    static final double MAX_HEAP_SHARE = 0.5;

    public ParseCacheProperties {
        if (!(heapShare > 0 && heapShare <= MAX_HEAP_SHARE)) {
            throw new IllegalArgumentException(
                    "app.graphql.parse-cache.heap-share must be above 0 and at most " + MAX_HEAP_SHARE + ": " + heapShare);
        }
    }
}
