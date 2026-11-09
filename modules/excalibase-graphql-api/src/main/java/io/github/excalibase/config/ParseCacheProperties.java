package io.github.excalibase.config;

import io.github.excalibase.compiler.ParsedQueries;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code app.graphql.parse-cache.heap-share}: the share of the maximum heap the parsed-query cache may use. */
@ConfigurationProperties(prefix = "app.graphql.parse-cache")
public record ParseCacheProperties(@DefaultValue("0.02") double heapShare) {

    public ParseCacheProperties {
        ParsedQueries.forHeapShare(heapShare, 1);
    }
}
