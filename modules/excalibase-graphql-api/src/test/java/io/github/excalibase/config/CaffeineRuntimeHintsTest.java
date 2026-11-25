package io.github.excalibase.config;

import static org.assertj.core.api.Assertions.assertThat;

import graphql.parser.Parser;
import io.github.excalibase.compiler.ParsedQueries;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The native image reaches the parse cache's generated Caffeine classes only through its reachability metadata. */
class CaffeineRuntimeHintsTest {

    private static final String METADATA = "/META-INF/native-image/io.github.excalibase/reachability-metadata.json";

    @Test
    @DisplayName("the classes the parse cache uses are registered for reflection")
    void theClassesTheParseCacheUsesAreRegisteredForReflection() throws Exception {
        ParsedQueries queries = new ParsedQueries(1_000, Parser::parse);
        queries.parse("{ a }");
        Object cache = field(field(queries, "documents"), "cache");
        Object nodeFactory = field(cache, "nodeFactory");

        String metadata = read();

        assertThat(metadata).contains("\"type\": \"" + cache.getClass().getName() + "\"");
        assertThat(metadata).contains("\"type\": \"" + nodeFactory.getClass().getName() + "\"");
    }

    private static String read() throws IOException {
        try (InputStream stream = CaffeineRuntimeHintsTest.class.getResourceAsStream(METADATA)) {
            assertThat(stream).as(METADATA).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
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
