package io.github.excalibase.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Numbers keep every digit between the database and the client: a decimal in a request or in a row the
 * database answered is read as a BigDecimal (never a double) with its scale, and written back as plain digits.
 */
@Configuration
public class JsonNumbersConfig {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer exactJsonNumbers() {
        return builder -> builder
                .featuresToEnable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                        JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
                .postConfigurer(mapper -> mapper.setNodeFactory(JsonNodeFactory.withExactBigDecimals(true)));
    }
}
