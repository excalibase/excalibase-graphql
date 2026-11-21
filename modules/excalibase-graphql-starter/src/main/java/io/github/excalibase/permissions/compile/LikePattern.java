package io.github.excalibase.permissions.compile;

import io.github.excalibase.permissions.PermissionEvaluationException;

import java.util.ArrayList;
import java.util.List;

/**
 * Postgres LIKE: {@code %} any run, {@code _} one code point, backslash escapes the next character.
 * Matched by a linear two-pointer scan that backtracks only to the last {@code %}, so a hostile pattern
 * and value cannot make the match explode the way a regex does.
 */
final class LikePattern {

    private static final int ANY_RUN = -1;
    private static final int ANY_ONE = -2;

    /** Patterns come from policies and claims; a bound keeps one match's work proportional to a sane size. */
    static final int MAX_PATTERN_CODE_POINTS = 4096;

    private final int[] tokens;

    private LikePattern(int[] tokens) {
        this.tokens = tokens;
    }

    static LikePattern compile(String like) {
        int[] codePoints = like.codePoints().toArray();
        if (codePoints.length > MAX_PATTERN_CODE_POINTS) {
            throw new PermissionEvaluationException(PermissionEvaluationException.INVALID_LIKE_PATTERN,
                    "A LIKE pattern is too long (more than " + MAX_PATTERN_CODE_POINTS + " characters)");
        }
        List<Integer> tokens = new ArrayList<>();
        int i = 0;
        while (i < codePoints.length) {
            int codePoint = codePoints[i];
            if (codePoint == '\\') {
                if (i + 1 == codePoints.length) {
                    throw new PermissionEvaluationException(PermissionEvaluationException.INVALID_LIKE_PATTERN,
                            "A LIKE pattern must not end with the escape character");
                }
                tokens.add(codePoints[i + 1]);
                i += 2;
                continue;
            }
            if (codePoint == '%') {
                if (tokens.isEmpty() || tokens.getLast() != ANY_RUN) tokens.add(ANY_RUN);
            } else {
                tokens.add(codePoint == '_' ? ANY_ONE : codePoint);
            }
            i++;
        }
        return new LikePattern(tokens.stream().mapToInt(Integer::intValue).toArray());
    }

    boolean matches(String value) {
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
