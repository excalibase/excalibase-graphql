package io.github.excalibase.errors;

import java.sql.SQLWarning;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResultTooLargeTest {

    private static SQLWarning cut() {
        return new SQLWarning("Result of json_arrayagg() was larger than max_allowed_packet", "HY000", 1301);
    }

    private static SQLWarning other() {
        return new SQLWarning("Data truncated", "01000", 1265);
    }

    private static SQLWarning chainOf(int others, SQLWarning last) {
        SQLWarning first = other();
        SQLWarning tail = first;
        for (int i = 1; i < others; i++) {
            SQLWarning next = other();
            tail.setNextWarning(next);
            tail = next;
        }
        if (last != null) {
            tail.setNextWarning(last);
        }
        return first;
    }

    @Test
    void noWarnings_isNotRefused() {
        assertThatCode(() -> ResultTooLarge.refuseIfCut(null, true)).doesNotThrowAnyException();
    }

    @Test
    void anotherWarning_isNotRefused() {
        assertThatCode(() -> ResultTooLarge.refuseIfCut(other(), true)).doesNotThrowAnyException();
    }

    @Test
    void theCutWarning_isRefused() {
        assertThatThrownBy(() -> ResultTooLarge.refuseIfCut(cut(), false)).isInstanceOf(DataErrorException.class);
    }

    @Test
    void theCutWarningBehindAnotherWarning_isRefused() {
        SQLWarning first = other();
        first.setNextWarning(cut());

        assertThatThrownBy(() -> ResultTooLarge.refuseIfCut(first, false)).isInstanceOf(DataErrorException.class);
    }

    @Test
    void aFullWarningListWithNoAnswer_isRefused_becauseTheCutWarningMayHaveBeenDropped() {
        SQLWarning full = chainOf(ResultTooLarge.WARNINGS_KEPT, null);

        assertThatThrownBy(() -> ResultTooLarge.refuseIfCut(full, true)).isInstanceOf(DataErrorException.class);
    }

    @Test
    void aFullWarningListWithARealAnswer_isNotRefused() {
        SQLWarning full = chainOf(ResultTooLarge.WARNINGS_KEPT, null);

        assertThatCode(() -> ResultTooLarge.refuseIfCut(full, false)).doesNotThrowAnyException();
    }

    @Test
    void aListJustUnderTheLimit_isNotRefused() {
        SQLWarning almost = chainOf(ResultTooLarge.WARNINGS_KEPT - 1, null);

        assertThatCode(() -> ResultTooLarge.refuseIfCut(almost, true)).doesNotThrowAnyException();
    }
}
