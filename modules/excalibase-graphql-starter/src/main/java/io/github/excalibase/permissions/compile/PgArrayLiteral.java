package io.github.excalibase.permissions.compile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads a one-dimensional Postgres array literal ({@code {"a","b"}}), the form array claims take
 * as session variables. An unquoted {@code NULL} element is SQL NULL; anything else malformed is refused.
 */
final class PgArrayLiteral {

    private final String body;
    private int position;

    private PgArrayLiteral(String body) {
        this.body = body;
    }

    static boolean looksLikeArray(String text) {
        return text.trim().startsWith("{");
    }

    static List<String> parse(String text) {
        String trimmed = text.trim();
        if (trimmed.length() < 2 || !trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw new IllegalArgumentException("Not an array literal");
        }
        String body = trimmed.substring(1, trimmed.length() - 1);
        return body.isBlank() ? List.of() : new PgArrayLiteral(body).elements();
    }

    private List<String> elements() {
        List<String> elements = new ArrayList<>();
        while (true) {
            skipSpaces();
            elements.add(position < body.length() && body.charAt(position) == '"' ? quoted() : unquoted());
            skipSpaces();
            if (position == body.length()) {
                return Collections.unmodifiableList(elements);
            }
            if (body.charAt(position++) != ',') {
                throw new IllegalArgumentException("Expected a comma in the array literal");
            }
        }
    }

    private String quoted() {
        StringBuilder element = new StringBuilder();
        position++;
        while (position < body.length()) {
            char next = body.charAt(position++);
            if (next == '"') {
                return element.toString();
            }
            element.append(next == '\\' ? escaped() : next);
        }
        throw new IllegalArgumentException("Unterminated quoted element");
    }

    private String unquoted() {
        StringBuilder element = new StringBuilder();
        boolean escapedAny = false;
        while (position < body.length() && body.charAt(position) != ',') {
            char next = body.charAt(position++);
            if (next == '{' || next == '}' || next == '"') {
                throw new IllegalArgumentException("Unexpected character in the array literal");
            }
            escapedAny |= next == '\\';
            element.append(next == '\\' ? escaped() : next);
        }
        String value = element.toString().strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Empty array element");
        }
        return !escapedAny && value.equalsIgnoreCase("NULL") ? null : value;
    }

    private char escaped() {
        if (position == body.length()) {
            throw new IllegalArgumentException("Dangling escape in the array literal");
        }
        return body.charAt(position++);
    }

    private void skipSpaces() {
        while (position < body.length() && Character.isWhitespace(body.charAt(position))) {
            position++;
        }
    }
}
