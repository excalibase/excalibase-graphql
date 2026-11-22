package io.github.excalibase.rest.compiler;

import io.github.excalibase.errors.DataError;
import io.github.excalibase.errors.DataErrorException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/** The JSON key each embed of one select comes back under. */
public final class EmbedKeys {

    /** What an alias may be: a plain name, which is also all that can be put inside a SQL or JSON string unescaped. */
    private static final Pattern ALIAS = Pattern.compile("[A-Za-z_]\\w{0,62}");
    private static final Set<String> RESERVED = Set.of("__proto__", "constructor", "prototype");

    private EmbedKeys() {
    }

    /** Refuses an alias that is not a plain name, or is one a JSON consumer treats specially. */
    public static void requireValidAlias(String alias) {
        if (!ALIAS.matcher(alias).matches() || RESERVED.contains(alias)) {
            throw new IllegalArgumentException("Invalid alias: use letters, digits and underscores, starting with a "
                    + "letter or underscore, up to 63 characters");
        }
    }

    /** An alias that is also a selected column would overwrite that column's value in the answer. */
    public static <T> void requireNoColumnCollision(List<T> embeds, Function<T, String> alias,
                                                    Set<String> selectedColumns) {
        for (T embed : embeds) {
            String key = alias.apply(embed);
            if (key != null && selectedColumns.contains(key)) {
                throw new DataErrorException(DataError.embedAliasCollidesWithColumn(key));
            }
        }
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
