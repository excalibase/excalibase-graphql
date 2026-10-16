package io.github.excalibase.mysql;

import io.github.excalibase.SqlDialect.FtsVariant;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A search turned into a MySQL BOOLEAN MODE query. Plain text requires every word, as Postgres plainto_tsquery
 * does: InnoDB's default stopwords and words shorter than its minimum token size are left out, since a required
 * word MySQL never indexes would match nothing. A phrase keeps its words in order; raw text is MySQL's own syntax.
 */
final class MysqlFullText {

    /** innodb_ft_min_token_size's default. */
    private static final int MIN_TOKEN_SIZE = 3;

    /** INFORMATION_SCHEMA.INNODB_FT_DEFAULT_STOPWORD. */
    private static final Set<String> STOPWORDS = Set.of("a", "about", "an", "are", "as", "at", "be", "by", "com",
            "de", "en", "for", "from", "how", "i", "in", "is", "it", "la", "of", "on", "or", "that", "the", "this", "to",
            "was", "what", "when", "where", "who", "will", "with", "und", "www");

    private MysqlFullText() {
    }

    static String query(FtsVariant variant, String text) {
        return switch (variant) {
            case PLAIN -> words(text).stream()
                    .filter(word -> word.length() >= MIN_TOKEN_SIZE && !STOPWORDS.contains(word.toLowerCase(Locale.ROOT)))
                    .map(word -> "+" + word)
                    .collect(Collectors.joining(" "));
            case PHRASE -> "\"" + String.join(" ", words(text)) + "\"";
            case RAW, WEB_SEARCH -> text;
        };
    }

    /** The words of {@code text}, with every character MySQL's boolean syntax reads as an operator dropped. */
    private static List<String> words(String text) {
        return Arrays.stream(text.split("[^\\p{L}\\p{N}_]+")).filter(word -> !word.isEmpty()).toList();
    }
}
