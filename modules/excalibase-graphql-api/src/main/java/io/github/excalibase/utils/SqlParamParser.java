package io.github.excalibase.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Package-private helper that does the character-by-character SQL parse for
 * {@link SqlUtils#resolveNamedParams(String, Map)}. Kept separate so each
 * sub-responsibility (named-param substitution, cast-suffix passthrough,
 * quoted-literal passthrough) lives in its own small method.
 */
final class SqlParamParser {

    private final String sql;
    private final Map<String, Object> params;
    private final StringBuilder result = new StringBuilder();
    private final List<Object> values = new ArrayList<>();
    private int i;

    SqlParamParser(String sql, Map<String, Object> params) {
        this.sql = sql;
        this.params = params;
    }

    SqlUtils.ResolvedSql parse() {
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == ':' && isNamedParamStart()) {
                readNamedParam();
            } else if (c == '\'') {
                readQuotedLiteral();
            } else {
                result.append(c);
                i++;
            }
        }
        return new SqlUtils.ResolvedSql(result.toString(), values);
    }

    private boolean isNamedParamStart() {
        return i + 1 < sql.length() && Character.isLetter(sql.charAt(i + 1));
    }

    private void readNamedParam() {
        int start = i + 1;
        int end = start;
        while (end < sql.length() && isIdentifierChar(sql.charAt(end))) {
            end++;
        }
        String paramName = sql.substring(start, end);
        if (!params.containsKey(paramName)) {
            // Unknown param — leave the ':' literal and move on
            result.append(sql.charAt(i));
            i++;
            return;
        }
        values.add(params.get(paramName));
        result.append('?');
        i = end;
        readCastSuffix();
    }

    private void readCastSuffix() {
        if (i + 1 >= sql.length() || sql.charAt(i) != ':' || sql.charAt(i + 1) != ':') {
            return;
        }
        int castEnd = i + 2;
        while (castEnd < sql.length() && isCastChar(sql.charAt(castEnd))) {
            char cc = sql.charAt(castEnd);
            if (cc == ' ' || cc == ',' || cc == ')' || cc == '\n') break;
            castEnd++;
        }
        result.append(sql, i, castEnd);
        i = castEnd;
    }

    private void readQuotedLiteral() {
        result.append(sql.charAt(i));
        i++;
        while (i < sql.length() && sql.charAt(i) != '\'') {
            result.append(sql.charAt(i));
            i++;
        }
        if (i < sql.length()) {
            result.append(sql.charAt(i));
            i++;
        }
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean isCastChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '"' || c == ' ';
    }
}
