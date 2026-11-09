package io.github.excalibase.config;

import io.github.excalibase.compiler.ParsedQueries;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ParseCacheProperties.class)
public class ParseCacheConfig {

    @Bean
    public ParsedQueries parsedQueries(ParseCacheProperties properties) {
        return ParsedQueries.forHeapShare(properties.heapShare(), Runtime.getRuntime().maxMemory());
    }
}
