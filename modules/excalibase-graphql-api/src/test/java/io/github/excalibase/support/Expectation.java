package io.github.excalibase.support;

/** One check on a server's answer; throws {@link AssertionError} when the answer is not what was expected. */
@FunctionalInterface
public interface Expectation {

    void check(HttpAnswer answer);
}
