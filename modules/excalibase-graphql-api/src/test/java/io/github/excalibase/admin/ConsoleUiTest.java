package io.github.excalibase.admin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsoleUiTest {

    @Test
    void legacyIsTheDefaultAndNextIsTheOtherChoice() {
        assertThat(ConsoleUi.parse("legacy")).isEqualTo(ConsoleUi.LEGACY);
        assertThat(ConsoleUi.parse("next")).isEqualTo(ConsoleUi.NEXT);
        assertThat(ConsoleUi.parse("NEXT")).isEqualTo(ConsoleUi.NEXT);
    }

    @Test
    void anythingElseRefusesTheStartAndNamesTheVariable() {
        for (String spelling : new String[] {"", " ", "new", "true", "nxt", "legacy,next"}) {
            assertThatThrownBy(() -> ConsoleUi.parse(spelling))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("EXCALIBASE_CONSOLE_UI must be legacy or next");
        }
        assertThatThrownBy(() -> ConsoleUi.parse(null)).isInstanceOf(IllegalStateException.class);
    }
}
