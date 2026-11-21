package io.github.excalibase.permissions.compile;

import io.github.excalibase.permissions.PermissionEvaluationException;
import io.github.excalibase.rls.LikePattern;

/**
 * Postgres LIKE for permission expressions. The matching itself is the linear matcher in rls-core, so a
 * hostile pattern and value cannot make it explode the way a regex does; this adds the refusals the
 * permission compiler reports: a pattern ending in the escape character, and one too long to be sane.
 */
final class PermissionLikePattern {

    /** Patterns come from policies and claims; a bound keeps one match's work proportional to a sane size. */
    static final int MAX_PATTERN_CODE_POINTS = 4096;

    private final LikePattern matcher;

    private PermissionLikePattern(LikePattern matcher) {
        this.matcher = matcher;
    }

    static PermissionLikePattern compile(String like) {
        if (like.codePointCount(0, like.length()) > MAX_PATTERN_CODE_POINTS) {
            throw new PermissionEvaluationException(PermissionEvaluationException.INVALID_LIKE_PATTERN,
                    "A LIKE pattern is too long (more than " + MAX_PATTERN_CODE_POINTS + " characters)");
        }
        LikePattern compiled = LikePattern.compile(like);
        if (compiled == null) {
            throw new PermissionEvaluationException(PermissionEvaluationException.INVALID_LIKE_PATTERN,
                    "A LIKE pattern must not end with the escape character");
        }
        return new PermissionLikePattern(compiled);
    }

    boolean matches(String value) {
        return matcher.matches(value);
    }
}
