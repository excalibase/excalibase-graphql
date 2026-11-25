package io.github.excalibase.rls;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL LIKE with Postgres semantics: {@code %} matches any run, {@code _} one code point, backslash
 * escapes the next character, everything else is literal, and newlines are ordinary characters.
 * Matched by a linear two-pointer scan that backtracks only to the last {@code %}, so a hostile
 * pattern and value cannot make the match explode the way a regex does.
 */
public final class LikePattern {

    private static final int ANY_RUN = -1;
    private static final int ANY_ONE = -2;

    private final int[] tokens;

    private LikePattern(int[] tokens) {
        this.tokens = tokens;
    }

    /**
     * @return the pattern, or {@code null} when it ends in an escape character. Postgres raises an
     *         error there, so the caller must treat the predicate as unevaluable and keep no row.
     */
    public static LikePattern compile(String like) {
        int[] codePoints = like.codePoints().toArray();
        List<Integer> tokens = new ArrayList<>();
        int i = 0;
        while (i < codePoints.length) {
            if (codePoints[i] == '\\') {
                if (i + 1 == codePoints.length) return null;
                tokens.add(codePoints[i + 1]);
                i += 2;
            } else {
                addWildcardOrLiteral(tokens, codePoints[i]);
                i++;
            }
        }
        return new LikePattern(tokens.stream().mapToInt(Integer::intValue).toArray());
    }

    private static void addWildcardOrLiteral(List<Integer> tokens, int codePoint) {
        if (codePoint == '%') {
            if (tokens.isEmpty() || tokens.getLast() != ANY_RUN) tokens.add(ANY_RUN);
        } else {
            tokens.add(codePoint == '_' ? ANY_ONE : codePoint);
        }
    }

    public boolean matches(String value) {
        int[] text = value.codePoints().toArray();
        int t = 0;
        int p = 0;
        int starP = -1;
        int starT = 0;
        while (t < text.length) {
            if (p < tokens.length && tokens[p] == ANY_RUN) {
                starP = p++;
                starT = t;
            } else if (p < tokens.length && (tokens[p] == ANY_ONE || tokens[p] == text[t])) {
                p++;
                t++;
            } else if (starP >= 0) {
                p = starP + 1;
                t = ++starT;
            } else {
                return false;
            }
        }
        while (p < tokens.length && tokens[p] == ANY_RUN) p++;
        return p == tokens.length;
    }
}
