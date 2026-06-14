package io.github.excalibase.rls;

/**
 * Structured shape for {@link MaskMode#PARTIAL} — Bytebase Studio's
 * partial-mask vocabulary expressed as a typed variant so the policy
 * editor renders a small form per variant rather than asking authors
 * to write opaque pattern strings.
 *
 * <p>v1.7 ship order (per RFC 0007):
 * <ol>
 *   <li>{@link KeepFirst}, {@link KeepLast}, {@link KeepBoth}
 *       — covers the 95% case (SSN, card-last-4, email-first-3).</li>
 *   <li>{@link MaskRange}, {@link Substring} — position-specific masks.</li>
 *   <li>{@link Regex} — power user; falls back to post-fetch where the
 *       dialect can't render {@code REGEXP_REPLACE}.</li>
 * </ol>
 */
public sealed interface PartialMaskSpec {

    /** Keep the first {@code n} characters of the value; mask the rest with {@code maskChar}. */
    record KeepFirst(int n, char maskChar) implements PartialMaskSpec {
        public KeepFirst { if (n < 0) throw new IllegalArgumentException("n must be >= 0"); }
    }

    /** Keep the last {@code n} characters of the value; mask the rest with {@code maskChar}. */
    record KeepLast(int n, char maskChar) implements PartialMaskSpec {
        public KeepLast { if (n < 0) throw new IllegalArgumentException("n must be >= 0"); }
    }

    /**
     * Keep the first {@code first} and last {@code last} characters; mask the
     * middle with {@code maskChar}. This is Bytebase's "Inner Outer Mask" —
     * SSN-style {@code "*****1234"} comes out of {@code KeepBoth(0, 4, '*')}.
     */
    record KeepBoth(int first, int last, char maskChar) implements PartialMaskSpec {
        public KeepBoth {
            if (first < 0 || last < 0) throw new IllegalArgumentException("first and last must be >= 0");
        }
    }

    /**
     * Mask a specific half-open range {@code [start, end)} of the value; keep
     * the rest. Bytebase's "Range Mask".
     */
    record MaskRange(int start, int end, char maskChar) implements PartialMaskSpec {
        public MaskRange {
            if (start < 0) throw new IllegalArgumentException("start must be >= 0");
            if (end < start) throw new IllegalArgumentException("end must be >= start");
        }
    }

    /**
     * Replace {@code length} characters starting at {@code start} with the
     * mask char; keep the rest. Bytebase's "Substring Mask".
     */
    record Substring(int start, int length, char maskChar) implements PartialMaskSpec {
        public Substring {
            if (start < 0 || length < 0) throw new IllegalArgumentException("start and length must be >= 0");
        }
    }

    /**
     * Regex-based replacement. {@code pattern} is matched against the value
     * and each match is replaced with {@code replacement}. SQL emission
     * requires the dialect to support {@code REGEXP_REPLACE}; consumers on
     * dialects without it fall back to the post-fetch path.
     */
    record Regex(String pattern, String replacement) implements PartialMaskSpec {
        public Regex {
            if (pattern == null || replacement == null) {
                throw new IllegalArgumentException("pattern and replacement must be non-null");
            }
        }
    }
}
