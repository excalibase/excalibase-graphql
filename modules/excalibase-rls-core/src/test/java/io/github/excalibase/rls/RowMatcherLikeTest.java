package io.github.excalibase.rls;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** LIKE must behave like Postgres LIKE: only % and _ are wildcards, backslash escapes, newlines match. */
class RowMatcherLikeTest {

    private static final UserContext CTX = new UserContext() {
        @Override public String userId() { return "u"; }
        @Override public String tenantId() { return null; }
        @Override public Set<String> roles() { return Set.of(); }
        @Override public Set<String> groupIds() { return Set.of(); }
    };

    private static boolean allowLike(String pattern, String value) {
        return matcher(PolicyEffect.ALLOW, RuleOperator.LIKE, pattern).matches("t", Map.of("c", value), CTX, Operation.SELECT);
    }

    private static boolean allowNotLike(String pattern, String value) {
        return matcher(PolicyEffect.ALLOW, RuleOperator.NOT_LIKE, pattern).matches("t", Map.of("c", value), CTX, Operation.SELECT);
    }

    private static boolean denyLikeKeepsRow(String pattern, String value) {
        Policy open = new Policy("a", "a", "t", PolicyEffect.ALLOW, Operation.ALL, LogicOperator.AND, 0, true,
            List.of(new Rule("c", FieldType.STRING, RuleOperator.IS_NOT_NULL, null)), List.of(Assignment.all()));
        Policy deny = policy(PolicyEffect.DENY, RuleOperator.LIKE, pattern);
        return new RowMatcher(List.of(open, deny)).matches("t", Map.of("c", value), CTX, Operation.SELECT);
    }

    private static RowMatcher matcher(PolicyEffect effect, RuleOperator op, String pattern) {
        return new RowMatcher(List.of(policy(effect, op, pattern)));
    }

    private static Policy policy(PolicyEffect effect, RuleOperator op, String pattern) {
        return new Policy(UUID.randomUUID().toString(), "p", "t", effect, Operation.ALL, LogicOperator.AND, 0, true,
            List.of(new Rule("c", FieldType.STRING, op, pattern)), List.of(Assignment.all()));
    }

    @Test
    void percentAndUnderscoreStillWork() {
        assertThat(allowLike("ab%", "abcdef")).isTrue();
        assertThat(allowLike("a_c", "abc")).isTrue();
        assertThat(allowLike("a_c", "abbc")).isFalse();
        assertThat(allowLike("abc", "abcd")).isFalse();
    }

    @Test
    void newlineIsMatchedByWildcards() {
        assertThat(allowLike("a%", "a\nb")).isTrue();
        assertThat(allowLike("a_b", "a\nb")).isTrue();
        assertThat(allowLike("%b", "a\nb")).isTrue();
        assertThat(allowLike("a\nb", "a\nb")).isTrue();
    }

    @Test
    void trailingNewlineIsNotIgnored() {
        assertThat(allowLike("abc", "abc\n")).isFalse();
        assertThat(allowLike("abc%", "abc\n")).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"+", "*", "?", "(", ")", "[", "]", "{", "}", "^", "$", "|", "."})
    void metacharactersInPatternAreLiteral(String meta) {
        assertThat(allowLike("a" + meta + "b", "a" + meta + "b")).isTrue();
        assertThat(allowLike("a" + meta + "b", "axb")).isFalse();
        assertThat(allowLike("a" + meta + "b", "ab")).isFalse();
    }

    @Test
    void regexLookalikePatternsDoNotActAsRegex() {
        assertThat(allowLike("a+", "aaa")).isFalse();
        assertThat(allowLike("a|b", "a")).isFalse();
        assertThat(allowLike("(a)", "a")).isFalse();
        assertThat(allowLike("[ab]", "a")).isFalse();
        assertThat(allowLike("^abc$", "abc")).isFalse();
        assertThat(allowLike("a{2}", "aa")).isFalse();
        assertThat(allowLike("a\\Eb", "a\\Eb")).isFalse();
    }

    @Test
    void metacharactersInValueAreLiteral() {
        assertThat(allowLike("%(%", "x(y")).isTrue();
        assertThat(allowLike("%$", "a$")).isTrue();
        assertThat(allowLike("a%", "a[b")).isTrue();
    }

    @Test
    void backslashEscapesPercentUnderscoreAndBackslash() {
        assertThat(allowLike("50\\%", "50%")).isTrue();
        assertThat(allowLike("50\\%", "500")).isFalse();
        assertThat(allowLike("a\\_c", "a_c")).isTrue();
        assertThat(allowLike("a\\_c", "abc")).isFalse();
        assertThat(allowLike("a\\\\c", "a\\c")).isTrue();
        assertThat(allowLike("a\\bc", "abc")).isTrue();
    }

    @Test
    void trailingEscapeFailsClosed() {
        // Postgres raises "LIKE pattern must not end with escape character"; no row may pass.
        assertThat(allowLike("abc\\", "abc\\")).isFalse();
        assertThat(allowLike("abc\\", "abc")).isFalse();
        assertThat(allowNotLike("abc\\", "abc")).isFalse();
        assertThat(denyLikeKeepsRow("abc\\", "zzz")).isFalse();
    }

    @Test
    void notLikeInvertsWithMetacharacters() {
        assertThat(allowNotLike("a+", "aaa")).isTrue();
        assertThat(allowNotLike("a+", "a+")).isFalse();
        assertThat(allowNotLike("a%", "a\nb")).isFalse();
    }

    @Test
    void denyLikeWithNewlineHidesRow() {
        assertThat(denyLikeKeepsRow("secret%", "secret\nmore")).isFalse();
        assertThat(denyLikeKeepsRow("secret%", "public")).isTrue();
    }
}
