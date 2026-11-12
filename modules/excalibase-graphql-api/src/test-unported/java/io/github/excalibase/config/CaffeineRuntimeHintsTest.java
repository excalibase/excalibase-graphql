package io.github.excalibase.config;

import graphql.parser.Parser;
import io.github.excalibase.compiler.ParsedQueries;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class CaffeineRuntimeHintsTest {

    @Test
    void theClassesTheParseCacheUsesAreRegisteredForReflection() throws Exception {
        ParsedQueries queries = new ParsedQueries(1_000, Parser::parse);
        queries.parse("{ a }");
        Object cache = field(field(queries, "documents"), "cache");
        Object nodeFactory = field(cache, "nodeFactory");

        RuntimeHints hints = new RuntimeHints();
        new ExcalibaseRuntimeHints().registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.reflection().onType(cache.getClass())).accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(nodeFactory.getClass())).accepts(hints);
    }

    private static Object field(Object owner, String name) throws Exception {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException _) {
                // look in the superclass
            }
        }
        throw new NoSuchFieldException(name);
    }
}
