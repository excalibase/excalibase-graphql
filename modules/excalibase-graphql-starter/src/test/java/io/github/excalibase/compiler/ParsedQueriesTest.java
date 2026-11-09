package io.github.excalibase.compiler;

import graphql.parser.InvalidSyntaxException;
import graphql.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParsedQueriesTest {

    private static final int BUDGET = 1600;

    private final AtomicInteger parses = new AtomicInteger();
    private final ParsedQueries queries = new ParsedQueries(BUDGET, text -> {
        parses.incrementAndGet();
        return Parser.parse(text);
    });

    private static String query(int length) {
        return "{ " + "a".repeat(length - 4) + " }";
    }

    @Test
    void repeatedQueryIsParsedOnce() {
        var first = queries.parse("{ a }");
        var second = queries.parse("{ a }");

        assertThat(second).isSameAs(first);
        assertThat(parses).hasValue(1);
    }

    @Test
    void queryAboveAShareOfTheBudgetIsAnsweredButNotKept() {
        String oversize = query(BUDGET / ParsedQueries.SINGLE_QUERY_SHARE_DIVISOR + 1);

        assertThat(queries.parse(oversize)).isNotNull();
        queries.parse(oversize);

        assertThat(parses).hasValue(2);
        assertThat(queries.entries()).isZero();
    }

    @Test
    void totalWeightStaysUnderTheBudget() {
        for (int i = 0; i < 200; i++) {
            queries.parse("{ a" + i + " " + "b".repeat(80) + " }");
        }

        assertThat(queries.weight()).isLessThanOrEqualTo(BUDGET);
        assertThat(queries.entries()).isPositive();
    }

    @Test
    void invalidQueryKeepsRaisingTheSyntaxErrorAndIsNotCached() {
        assertThatThrownBy(() -> queries.parse("{ a")).isInstanceOf(InvalidSyntaxException.class);
        assertThatThrownBy(() -> queries.parse("{ a")).isInstanceOf(InvalidSyntaxException.class);

        assertThat(queries.entries()).isZero();
        assertThat(parses).hasValue(2);
    }

    @Test
    void heapShareOutsideZeroToOneIsRefused() {
        assertThatThrownBy(() -> ParsedQueries.forHeapShare(0, 1L << 30)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ParsedQueries.forHeapShare(0.6, 1L << 30)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void budgetIsDerivedFromTheHeap() {
        long heap = 1L << 30;
        var derived = ParsedQueries.forHeapShare(0.02, heap);

        String big = query((int) (heap * 0.02 / ParsedQueries.RETAINED_BYTES_PER_CHAR / ParsedQueries.SINGLE_QUERY_SHARE_DIVISOR) + 100);
        derived.parse(big);

        assertThat(derived.entries()).isZero();
    }
}
