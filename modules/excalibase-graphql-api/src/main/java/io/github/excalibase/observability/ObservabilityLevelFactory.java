package io.github.excalibase.observability;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

/** The configured monitoring level; an unknown level stops start-up here instead of being guessed. */
@Factory
class ObservabilityLevelFactory {

    @Singleton
    ObservabilityLevel level(@Value("${excalibase.observability.level:}") String configured) {
        return ObservabilityLevel.parse(configured);
    }
}
