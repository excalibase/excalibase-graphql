package io.github.excalibase.support;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hamcrest.Matcher;
import org.hamcrest.MatcherAssert;

/** Checks on an {@link HttpAnswer}: its status, its body and the values at JSON paths in it. */
public final class Expect {

    private Expect() {
    }

    public static StatusExpectation status() {
        return new StatusExpectation();
    }

    public static ContentExpectation content() {
        return new ContentExpectation();
    }

    public static JsonPathExpectation jsonPath(String path) {
        return new JsonPathExpectation(path);
    }

    public static Expectation jsonPath(String path, Matcher<?> matcher) {
        return answer -> MatcherAssert.assertThat(describe(answer, path), actualAt(answer, path), asObjectMatcher(matcher));
    }

    @SuppressWarnings("unchecked")
    private static Matcher<Object> asObjectMatcher(Matcher<?> matcher) {
        return (Matcher<Object>) matcher;
    }

    private static String describe(HttpAnswer answer, String path) {
        return "JSON path " + path + " in " + answer.body();
    }

    private static Object actualAt(HttpAnswer answer, String path) {
        return JsonPaths.select(answer.json(), path).value().orElse(null);
    }

    public static final class StatusExpectation {

        public Expectation is(int status) {
            return answer -> check(answer, status);
        }

        public Expectation is2xxSuccessful() {
            return inClass(2);
        }

        public Expectation is4xxClientError() {
            return inClass(4);
        }

        private static Expectation inClass(int hundreds) {
            return answer -> {
                if (answer.status() / 100 != hundreds) {
                    throw new AssertionError("Status expected: " + hundreds + "xx but was:<" + answer.status()
                            + "> body: " + answer.body());
                }
            };
        }

        public Expectation isOk() {
            return is(200);
        }

        public Expectation isCreated() {
            return is(201);
        }

        public Expectation isBadRequest() {
            return is(400);
        }

        public Expectation isUnauthorized() {
            return is(401);
        }

        public Expectation isForbidden() {
            return is(403);
        }

        public Expectation isNotFound() {
            return is(404);
        }

        public Expectation isConflict() {
            return is(409);
        }

        public Expectation isServiceUnavailable() {
            return is(503);
        }

        private static void check(HttpAnswer answer, int expected) {
            if (answer.status() != expected) {
                throw new AssertionError("Status expected:<" + expected + "> but was:<" + answer.status()
                        + "> body: " + answer.body());
            }
        }
    }

    public static final class ContentExpectation {

        public Expectation string(Matcher<? super String> matcher) {
            return answer -> MatcherAssert.assertThat("response body", answer.body(), matcher);
        }
    }

    public static final class JsonPathExpectation {

        private final String path;

        private JsonPathExpectation(String path) {
            this.path = path;
        }

        public Expectation value(Object expected) {
            return answer -> {
                Object actual = unwrapSingle(answer, expected);
                if (!JsonPaths.sameValue(expected, actual)) {
                    throw new AssertionError(describe(answer, path) + " expected:<" + expected + "> but was:<" + actual + ">");
                }
            };
        }

        public Expectation value(Matcher<?> matcher) {
            return jsonPath(path, matcher);
        }

        public Expectation exists() {
            return answer -> {
                if (!present(answer)) {
                    throw new AssertionError("No value at " + describe(answer, path));
                }
            };
        }

        public Expectation doesNotExist() {
            return answer -> {
                Optional<Object> value = JsonPaths.select(answer.json(), path).value();
                boolean absent = value.isEmpty() || value.get() instanceof List<?> list && list.isEmpty();
                if (!absent) {
                    throw new AssertionError("Expected no value at " + describe(answer, path) + " but was " + value.get());
                }
            };
        }

        public Expectation isEmpty() {
            return answer -> {
                Object actual = actualAt(answer, path);
                boolean empty = actual == null || actual instanceof String text && text.isEmpty()
                        || actual instanceof List<?> list && list.isEmpty() || actual instanceof Map<?, ?> map && map.isEmpty();
                if (!empty) {
                    throw new AssertionError(describe(answer, path) + " expected empty but was:<" + actual + ">");
                }
            };
        }

        public Expectation isArray() {
            return typed("an array", List.class);
        }

        public Expectation isNumber() {
            return typed("a number", Number.class);
        }

        public Expectation isString() {
            return typed("a string", String.class);
        }

        public Expectation isBoolean() {
            return typed("a boolean", Boolean.class);
        }

        private Expectation typed(String description, Class<?> type) {
            return answer -> {
                Object actual = actualAt(answer, path);
                if (!type.isInstance(actual)) {
                    throw new AssertionError(describe(answer, path) + " expected " + description + " but was:<" + actual + ">");
                }
            };
        }

        private boolean present(HttpAnswer answer) {
            Optional<Object> value = JsonPaths.select(answer.json(), path).value();
            return value.isPresent() && value.get() != null && !(value.get() instanceof List<?> list && list.isEmpty());
        }

        /** A path that selects several nodes answers a list; one expected scalar then matches its only element. */
        private Object unwrapSingle(HttpAnswer answer, Object expected) {
            Object actual = actualAt(answer, path);
            if (actual instanceof List<?> list && !(expected instanceof List<?>) && list.size() == 1) {
                return list.getFirst();
            }
            return actual;
        }
    }
}
