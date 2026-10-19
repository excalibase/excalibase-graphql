package io.github.excalibase.rest.parser;

import io.github.excalibase.FilterCapabilities;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class FilterParser {

    /** Every operator the engine parses; whether the database runs one is the compiler's check. */
    private static final Set<String> KNOWN_OPERATORS = FilterCapabilities.knownRest();

    private FilterParser() {}

    public record FilterCondition(String column, String operator, String value, boolean negated) {}

    public static FilterCondition parse(String column, String expression) {
        boolean negated = false;
        String remaining = expression;

        if (remaining.startsWith("not.")) {
            negated = true;
            remaining = remaining.substring(4);
        }

        int dotIdx = remaining.indexOf('.');
        if (dotIdx < 0) {
            return new FilterCondition(column, remaining, "", negated);
        }

        String operator = remaining.substring(0, dotIdx);
        String value = remaining.substring(dotIdx + 1);

        if (KNOWN_OPERATORS.contains(operator)) {
            return new FilterCondition(column, operator, value, negated);
        }

        return new FilterCondition(column, "eq", expression, false);
    }

    /**
     * A logic group: its filters and nested groups joined by AND when {@code and}, else by OR, the whole
     * negated when {@code negated}.
     */
    public record LogicGroup(boolean and, boolean negated, List<FilterCondition> filters, List<LogicGroup> groups) {}

    private static final int MAX_LOGIC_DEPTH = 10;

    /**
     * PostgREST's {@code or=(…)} / {@code and=(…)} value, whose members are filters ({@code col.op.value}) or
     * nested groups ({@code and(…)}, {@code or(…)}, {@code not.and(…)}, {@code not.or(…)}).
     */
    public static LogicGroup parseLogic(String expression, boolean and, boolean negated) {
        return parseLogic(expression, and, negated, 0);
    }

    private static LogicGroup parseLogic(String expression, boolean and, boolean negated, int depth) {
        if (depth > MAX_LOGIC_DEPTH) {
            throw new IllegalArgumentException("Logic groups nest deeper than " + MAX_LOGIC_DEPTH);
        }
        String inner = expression.trim();
        if (!inner.startsWith("(") || !inner.endsWith(")")) {
            throw new IllegalArgumentException("A logic group must be enclosed in parentheses: " + expression);
        }
        inner = inner.substring(1, inner.length() - 1);
        List<FilterCondition> filters = new ArrayList<>();
        List<LogicGroup> groups = new ArrayList<>();
        for (String member : splitRespectingParens(inner)) {
            String part = member.trim();
            boolean notGroup = part.startsWith("not.");
            String bare = notGroup ? part.substring(4) : part;
            if (bare.startsWith("and(") || bare.startsWith("or(")) {
                boolean nestedAnd = bare.startsWith("and(");
                groups.add(parseLogic(bare.substring(nestedAnd ? 3 : 2), nestedAnd, notGroup, depth + 1));
                continue;
            }
            int firstDot = part.indexOf('.');
            if (firstDot <= 0) {
                throw new IllegalArgumentException("Invalid filter in logic group: " + part);
            }
            filters.add(parse(part.substring(0, firstDot), part.substring(firstDot + 1)));
        }
        return new LogicGroup(and, negated, filters, groups);
    }

    public static List<FilterCondition> parseOr(String expression) {
        List<FilterCondition> conditions = new ArrayList<>();
        String inner = expression;
        if (inner.startsWith("(") && inner.endsWith(")")) {
            inner = inner.substring(1, inner.length() - 1);
        }

        for (String part : splitRespectingParens(inner)) {
            int firstDot = part.indexOf('.');
            if (firstDot < 0) continue;
            conditions.add(parse(part.substring(0, firstDot), part.substring(firstDot + 1)));
        }
        return conditions;
    }

    static List<String> splitRespectingParens(String input) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (ch == '(') depth++;
            else if (ch == ')') depth--;
            else if (ch == ',' && depth == 0) {
                parts.add(input.substring(start, i));
                start = i + 1;
            }
        }
        if (start < input.length()) {
            parts.add(input.substring(start));
        }
        return parts;
    }
}
