package io.github.excalibase.rest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseRowsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void anArrayIsItsRows() {
        assertThat(DatabaseRows.parse(MAPPER, "[{\"id\":1},{\"id\":2}]")).hasSize(2);
    }

    @Test
    void noAnswerIsNoRows() {
        assertThat(DatabaseRows.parse(MAPPER, null)).isEmpty();
    }

    @Test
    void anAnswerThatIsNotJson_isAFailure_notNoRows() {
        assertThatThrownBy(() -> DatabaseRows.parse(MAPPER, "[{\"id\":1},"))
                .isInstanceOf(IllegalStateException.class);
    }
}
