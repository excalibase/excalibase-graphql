package io.github.excalibase.sqlite;

import java.util.Locale;
import java.util.Set;

/** Tells whether a statement takes SQLite's write lock, reading only what the statement says, never what it quotes. */
final class SqliteWrites {

    private static final Set<String> STATEMENT_WRITES = Set.of("INSERT", "UPDATE", "DELETE", "REPLACE", "CREATE", "DROP",
            "ALTER", "VACUUM", "REINDEX");
    private static final Set<String> CTE_BODY_WRITES = Set.of("INSERT", "UPDATE", "DELETE", "REPLACE");
    private static final Set<String> CTE_BODY_STARTS = Set.of("SELECT", "VALUES", "INSERT", "UPDATE", "DELETE", "REPLACE");

    private SqliteWrites() {
    }

    /** Whether {@code sql} writes; what cannot be told is read as a read and left to the busy timeout. */
    static boolean writes(String sql) {
        if (sql == null) {
            return false;
        }
        String bare = withoutQuotesAndComments(sql).strip().toUpperCase(Locale.ROOT);
        String first = firstWord(bare);
        return switch (first) {
            case "WITH" -> CTE_BODY_WRITES.contains(topLevelStatement(bare));
            case "PRAGMA" -> bare.contains("=");
            case "BEGIN" -> bare.contains("IMMEDIATE") || bare.contains("EXCLUSIVE");
            default -> STATEMENT_WRITES.contains(first);
        };
    }

    private static String firstWord(String text) {
        int end = 0;
        while (end < text.length() && Character.isLetter(text.charAt(end))) {
            end++;
        }
        return text.substring(0, end);
    }

    /** The statement a WITH introduces: the first keyword outside every parenthesis, which is after the CTE list. */
    private static String topLevelStatement(String text) {
        int depth = 0;
        int index = 0;
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && Character.isLetter(c)) {
                int end = index;
                while (end < text.length() && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_')) {
                    end++;
                }
                String word = text.substring(index, end);
                if (CTE_BODY_STARTS.contains(word)) {
                    return word;
                }
                index = end;
                continue;
            }
            index++;
        }
        return "";
    }

    /** {@code sql} with comments, string literals and quoted names blanked out, so a word inside one is no keyword. */
    static String withoutQuotesAndComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int index = 0;
        while (index < sql.length()) {
            char c = sql.charAt(index);
            if (c == '-' && sql.startsWith("--", index)) {
                index = skipTo(sql, index, "\n", out);
            } else if (c == '/' && sql.startsWith("/*", index)) {
                index = skipTo(sql, index, "*/", out);
            } else if (c == '\'' || c == '"' || c == '`') {
                index = skipQuoted(sql, index, c, out);
            } else if (c == '[') {
                index = skipTo(sql, index, "]", out);
            } else {
                out.append(c);
                index++;
            }
        }
        return out.toString();
    }

    private static int skipTo(String sql, int from, String end, StringBuilder out) {
        int found = sql.indexOf(end, from + 1);
        out.append(' ');
        return found < 0 ? sql.length() : found + end.length();
    }

    /** A quote closes at the next one that is not doubled. */
    private static int skipQuoted(String sql, int from, char quote, StringBuilder out) {
        int index = from + 1;
        while (index < sql.length()) {
            if (sql.charAt(index) == quote) {
                if (index + 1 < sql.length() && sql.charAt(index + 1) == quote) {
                    index += 2;
                    continue;
                }
                out.append(' ');
                return index + 1;
            }
            index++;
        }
        out.append(' ');
        return sql.length();
    }
}
