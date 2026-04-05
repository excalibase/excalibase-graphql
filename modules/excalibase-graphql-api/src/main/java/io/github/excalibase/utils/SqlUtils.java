package io.github.excalibase.utils;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Static SQL utilities: named-parameter resolution and JDBC type mapping.
 */
public class SqlUtils {

    private SqlUtils() {}

    public record ResolvedSql(String sql, List<Object> values) {}

    public static ResolvedSql resolveNamedParams(String sql, Map<String, Object> params) {
        List<Object> values = new ArrayList<>();
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < sql.length()) {
            if (sql.charAt(i) == ':' && i + 1 < sql.length() && Character.isLetter(sql.charAt(i + 1))) {
                int start = i + 1;
                int end = start;
                while (end < sql.length() && (Character.isLetterOrDigit(sql.charAt(end)) || sql.charAt(end) == '_')) {
                    end++;
                }
                String paramName = sql.substring(start, end);
                if (params.containsKey(paramName)) {
                    values.add(params.get(paramName));
                    result.append('?');
                    i = end;
                    if (i < sql.length() && sql.charAt(i) == ':' && i + 1 < sql.length() && sql.charAt(i + 1) == ':') {
                        int castEnd = i + 2;
                        while (castEnd < sql.length() && (Character.isLetterOrDigit(sql.charAt(castEnd))
                                || sql.charAt(castEnd) == '_' || sql.charAt(castEnd) == '.'
                                || sql.charAt(castEnd) == '"' || sql.charAt(castEnd) == ' ')) {
                            if (sql.charAt(castEnd) == ' ' || sql.charAt(castEnd) == ',' || sql.charAt(castEnd) == ')'
                                    || sql.charAt(castEnd) == '\n') break;
                            castEnd++;
                        }
                        result.append(sql, i, castEnd);
                        i = castEnd;
                    }
                } else {
                    result.append(sql.charAt(i));
                    i++;
                }
            } else if (sql.charAt(i) == '\'') {
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
            } else {
                result.append(sql.charAt(i));
                i++;
            }
        }
        return new ResolvedSql(result.toString(), values);
    }

    public static int sqlTypeFor(String pgType) {
        if (pgType == null) return Types.OTHER;
        String t = pgType.toLowerCase();
        if (t.contains("int") || t.equals("bigint")) return Types.BIGINT;
        if (t.contains("numeric") || t.contains("decimal")) return Types.NUMERIC;
        if (t.contains("float") || t.contains("double") || t.equals("real")) return Types.DOUBLE;
        if (t.equals("boolean") || t.equals("bool")) return Types.BOOLEAN;
        if (t.equals("text") || t.startsWith("varchar") || t.startsWith("character")) return Types.VARCHAR;
        return Types.OTHER;
    }
}
