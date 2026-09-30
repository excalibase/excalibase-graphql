package io.github.excalibase.permissions.compile;

import io.github.excalibase.permissions.PermissionEvaluationException;

import java.util.regex.Pattern;

/** Postgres LIKE: {@code %} any run, {@code _} one character, backslash escapes the next character. */
final class LikePattern {

    private LikePattern() {
    }

    static Pattern compile(String like) {
        StringBuilder regex = new StringBuilder();
        int[] codePoints = like.codePoints().toArray();
        for (int i = 0; i < codePoints.length; i++) {
            int codePoint = codePoints[i];
            if (codePoint == '\\') {
                if (++i == codePoints.length) {
                    throw new PermissionEvaluationException(PermissionEvaluationException.INVALID_LIKE_PATTERN,
                            "A LIKE pattern must not end with the escape character");
                }
                regex.append(literal(codePoints[i]));
            } else if (codePoint == '%') {
                regex.append(".*");
            } else if (codePoint == '_') {
                regex.append('.');
            } else {
                regex.append(literal(codePoint));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }

    private static String literal(int codePoint) {
        return Pattern.quote(new String(Character.toChars(codePoint)));
    }
}
