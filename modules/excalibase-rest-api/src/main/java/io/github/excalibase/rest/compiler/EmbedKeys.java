package io.github.excalibase.rest.compiler;

import io.github.excalibase.errors.DataError;
import io.github.excalibase.errors.DataErrorException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** The JSON key each embed of one select comes back under. */
public final class EmbedKeys {

    private EmbedKeys() {
    }

    /**
     * An embed's alias when it has one; otherwise its table name, even when it was named with its schema, unless
     * another embed without an alias of the same select would then share the key, when it keeps the name as written.
     */
    public static <T> List<String> of(List<T> embeds, Function<T, String> name, Function<T, String> alias) {
        return embeds.stream().map(embed -> {
            if (alias.apply(embed) != null) {
                return alias.apply(embed);
            }
            String written = name.apply(embed);
            boolean taken = embeds.stream().anyMatch(other -> other != embed && alias.apply(other) == null
                    && bare(name.apply(other)).equals(bare(written)));
            return taken ? written : bare(written);
        }).toList();
    }

    /** Two embeds answering under one key would overwrite each other: refused, naming the way out. */
    public static void requireDistinct(List<String> keys) {
        Set<String> seen = new HashSet<>();
        for (String key : keys) {
            if (!seen.add(key)) {
                throw new DataErrorException(DataError.embedKeyCollision(key));
            }
        }
    }

    private static String bare(String written) {
        return written.substring(written.lastIndexOf('.') + 1);
    }
}
