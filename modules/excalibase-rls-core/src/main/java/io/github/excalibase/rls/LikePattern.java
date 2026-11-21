package io.github.excalibase.rls;

import java.util.regex.Pattern;

/**
 * Translates a SQL LIKE pattern to a regex with Postgres semantics: only
 * {@code %} (any run) and {@code _} (any one char) are wildcards, backslash
 * escapes the next character, every other character is literal, and newlines
 * are matched by the wildcards.
 */
final class LikePattern {

    private static final char ESCAPE = '\\';

    private LikePattern() {
    }

    /**
     * @return the compiled pattern, or {@code null} when the LIKE pattern ends in
     *         an escape character. Postgres raises an error there, so the caller
     *         must treat the predicate as unevaluable and keep no row.
     */
    static Pattern compile(String like) {
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < like.length(); i++) {
            char c = like.charAt(i);
            if (c == ESCAPE) {
                if (++i == like.length()) return null;
                literal.append(like.charAt(i));
            } else if (c == '%' || c == '_') {
                flush(regex, literal);
                regex.append(c == '%' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        flush(regex, literal);
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }

    private static void flush(StringBuilder regex, StringBuilder literal) {
        if (literal.isEmpty()) return;
        regex.append(Pattern.quote(literal.toString()));
        literal.setLength(0);
    }
}
