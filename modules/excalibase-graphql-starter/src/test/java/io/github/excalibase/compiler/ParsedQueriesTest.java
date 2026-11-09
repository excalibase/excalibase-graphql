package io.github.excalibase.compiler;

import graphql.parser.InvalidSyntaxException;
import graphql.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParsedQueriesTest {

    private final AtomicInteger parses = new AtomicInteger();
    private final ParsedQueries queries = new ParsedQueries(2, text -> {
        parses.incrementAndGet();
        return Parser.parse(text);
    });

    @Test
    void repeatedQueryIsParsedOnce() {
        var first = queries.parse("{ a }");
        var second = queries.parse("{ a }");

        assertThat(second).isSameAs(first);
        assertThat(parses).hasValue(1);
    }

    @Test
    void cacheIsBoundedAndEvictsLeastRecentlyUsed() {
        queries.parse("{ a }");
        queries.parse("{ b }");
        queries.parse("{ a }");
        queries.parse("{ c }");

        assertThat(queries.size()).isEqualTo(2);
        queries.parse("{ a }");
        assertThat(parses).hasValue(3);
        queries.parse("{ b }");
        assertThat(parses).hasValue(4);
    }

    @Test
    void invalidQueryKeepsRaisingTheSyntaxErrorAndIsNotCached() {
        assertThatThrownBy(() -> queries.parse("{ a")).isInstanceOf(InvalidSyntaxException.class);
        assertThatThrownBy(() -> queries.parse("{ a")).isInstanceOf(InvalidSyntaxException.class);

        assertThat(queries.size()).isZero();
        assertThat(parses).hasValue(2);
    }
}
