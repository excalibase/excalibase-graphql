package io.github.excalibase.security;

import io.github.excalibase.errors.DataError;
import io.github.excalibase.errors.DataErrors;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A pause met while a request runs (the pool expired between the project check and the query) is the same 503. */
class ProjectPausedAnswerTest {

    @Test
    void aPauseMetWhileARequestRuns_isDescribedAs503ProjectPaused() {
        DataError error = DataErrors.describe(new RuntimeException("wrapped", new ProjectPausedException("p1")), null)
                .orElseThrow();

        assertThat(error.status()).isEqualTo(503);
        assertThat(error.code()).isEqualTo("project_paused");
        assertThat(error.message()).isEqualTo("Project paused").doesNotContain("p1");
    }
}
