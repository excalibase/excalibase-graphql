package io.github.excalibase.rest.parser;

import io.github.excalibase.errors.DataError;
import io.github.excalibase.errors.DataErrorException;
import io.github.excalibase.rest.compiler.EmbedKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SelectParser {

    private static final Logger log = LoggerFactory.getLogger(SelectParser.class);
    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_$]{0,63}");
    /** A name written in double quotes: what an identifier takes, plus space, dot, hash and hyphen. */
    private static final Pattern QUOTED = Pattern.compile("\"([\\p{L}\\p{N}_$ .#-]{1,64})\"");
    private static final String STAR = "*";
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private SelectParser() {}

    /** Whether names outside the allowlist are refused, and the position a name was written in. */
    private record Mode(boolean strict) {}

    private enum Position { COLUMN, ALIAS, EMBED, HINT }

    /**
     * A name as the database knows it: a plain identifier, or a double-quoted name unquoted. Anything else is
     * refused when strict, and otherwise passed on as written to be filtered against the table's real columns.
     */
    private static String name(String raw, Position position, Mode mode) {
        if (IDENTIFIER.matcher(raw).matches()) return raw;
        Matcher quoted = QUOTED.matcher(raw);
        if (quoted.matches()) return quoted.group(1);
        if (mode.strict()) throw new IllegalArgumentException("Invalid " + position.name().toLowerCase() + " name in select");
        warnOnce(position, raw);
        return raw;
    }

    private static String column(String raw, Mode mode) {
        return STAR.equals(raw) ? raw : name(raw, Position.COLUMN, mode);
    }

    /** An embed's table: one name, or schema.table. */
    private static String relation(String raw, Mode mode) {
        String[] segments = raw.split("\\.", -1);
        if (segments.length > 2 && mode.strict()) throw new IllegalArgumentException("Invalid embed name in select");
        if (segments.length > 2) {
            warnOnce(Position.EMBED, raw);
            return raw;
        }
        List<String> names = new ArrayList<>();
        for (String segment : segments) names.add(name(segment, Position.EMBED, mode));
        return String.join(".", names);
    }

    private static boolean skipped(String part, Mode mode) {
        if (!part.isEmpty()) return false;
        if (mode.strict()) throw new IllegalArgumentException("Empty item in select");
        return true;
    }

    /** For tests: lets a name warn again. */
    static void forgetWarnings() {
        WARNED.clear();
    }

    /** One warning per position and kind of mismatch, naming no value. */
    private static void warnOnce(Position position, String raw) {
        Set<String> kinds = mismatchKinds(raw);
        String where = position.name().toLowerCase();
        if (WARNED.add(where + " " + kinds)) {
            log.warn("select {} name outside the allowlist ({}); app.rest.strict-select-names=true would refuse it",
                    where, kinds);
        }
    }

    private static Set<String> mismatchKinds(String raw) {
        Set<String> kinds = new TreeSet<>();
        if (raw.length() > 64) kinds.add("too long");
        if (!raw.isEmpty() && (Character.isDigit(raw.charAt(0)) || raw.charAt(0) == '$')) kinds.add("leading digit or dollar");
        for (char ch : raw.toCharArray()) {
            if (!Character.isLetterOrDigit(ch) && ch != '_' && ch != '$') kinds.add(kindOf(ch));
        }
        return kinds;
    }

    private static String kindOf(char ch) {
        if (Character.isWhitespace(ch)) return "whitespace";
        return switch (ch) {
            case '-' -> "hyphen";
            case '.' -> "dot";
            case '"', '\'', '\\' -> "quote or backslash";
            default -> "other character";
        };
    }

    public record SelectResult(List<String> columns, List<EmbedSpec> embeds, Map<String, String> aliases) {}
    /** {@code alias} is the JSON key the caller asked for ({@code creator:users!created_by(...)}), or null. */
    public record EmbedSpec(String relationName, List<String> columns, String fkHint, List<EmbedSpec> children,
                            String alias) {
        public EmbedSpec(String relationName, List<String> columns) {
            this(relationName, columns, null, List.of(), null);
        }
        public EmbedSpec(String relationName, List<String> columns, String fkHint) {
            this(relationName, columns, fkHint, List.of(), null);
        }
        public EmbedSpec(String relationName, List<String> columns, String fkHint, List<EmbedSpec> children) {
            this(relationName, columns, fkHint, children, null);
        }
    }

    /** An embed's {@code [alias:]name[!hint]} before its parenthesis. */
    private record Header(String name, String hint, String alias) {
        static Header of(String prefix) {
            String alias = null;
            String rest = prefix;
            int colon = prefix.indexOf(':');
            int bang = prefix.indexOf('!');
            if (colon >= 0 && (bang < 0 || colon < bang)) {
                alias = prefix.substring(0, colon).trim();
                EmbedKeys.requireValidAlias(alias);
                rest = prefix.substring(colon + 1).trim();
            }
            int hintAt = rest.indexOf('!');
            if (hintAt > 0) {
                return new Header(rest.substring(0, hintAt), rest.substring(hintAt + 1), alias);
            }
            return new Header(rest, null, alias);
        }
    }

    /** The lenient parse: names outside the allowlist pass on to be filtered against the table's columns. */
    public static SelectResult parse(String select) {
        return parse(select, false);
    }

    public static SelectResult parse(String select, boolean strictNames) {
        Mode mode = new Mode(strictNames);
        if (select == null || select.isBlank() || "*".equals(select.trim())) {
            return new SelectResult(List.of(), List.of(), Map.of());
        }

        List<String> columns = new ArrayList<>();
        List<EmbedSpec> embeds = new ArrayList<>();
        Map<String, String> aliases = new HashMap<>();

        for (String part : splitRespectingParens(select)) {
            part = part.trim();
            if (skipped(part, mode)) continue;
            int parenStart = part.indexOf('(');
            if (parenStart > 0 && part.endsWith(")")) {
                String prefix = part.substring(0, parenStart).trim();
                String innerContent = part.substring(parenStart + 1, part.length() - 1);
                embeds.add(parseEmbedSpec(Header.of(prefix), innerContent, mode));
            } else if (part.contains(":")) {
                String[] aliasParts = part.split(":", 2);
                String alias = name(aliasParts[0].trim(), Position.ALIAS, mode);
                String column = column(aliasParts[1].trim(), mode);
                columns.add(column);
                aliases.put(column, alias);
            } else {
                columns.add(column(part, mode));
            }
        }

        for (EmbedSpec embed : embeds) {
            if (embed.alias() != null && aliases.containsValue(embed.alias())) {
                throw new DataErrorException(DataError.embedAliasCollidesWithColumn(embed.alias()));
            }
        }
        return new SelectResult(columns, embeds, aliases);
    }

    private static EmbedSpec parseEmbedSpec(Header header, String innerContent, Mode mode) {
        List<String> plainCols = new ArrayList<>();
        List<EmbedSpec> children = new ArrayList<>();
        for (String part : splitRespectingParens(innerContent)) {
            part = part.trim();
            if (skipped(part, mode)) continue;
            int ip = part.indexOf('(');
            if (ip > 0 && part.endsWith(")")) {
                String childPrefix = part.substring(0, ip).trim();
                String childInner = part.substring(ip + 1, part.length() - 1);
                children.add(parseEmbedSpec(Header.of(childPrefix), childInner, mode));
            } else {
                plainCols.add(column(part, mode));
            }
        }
        String hint = header.hint() == null ? null : name(header.hint(), Position.HINT, mode);
        return new EmbedSpec(relation(header.name(), mode), plainCols, hint, children, header.alias());
    }

    private static List<String> splitRespectingParens(String input) {
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
