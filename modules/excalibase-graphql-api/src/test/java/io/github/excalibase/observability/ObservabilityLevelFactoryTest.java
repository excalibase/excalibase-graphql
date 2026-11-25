package io.github.excalibase.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micronaut.context.annotation.Context;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ObservabilityLevelFactoryTest {

    @Test
    @DisplayName("the level is read at start-up, so an unknown level stops the engine before it serves anything")
    void levelIsReadAtStart() throws Exception {
        Method level = ObservabilityLevelFactory.class.getDeclaredMethod("level", String.class);

        assertThat(level.isAnnotationPresent(Context.class)).isTrue();
    }
}
