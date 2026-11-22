package io.github.excalibase.rest.parser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SelectParser {

    private SelectParser() {}

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
            if (colon > 0 && (bang < 0 || colon < bang)) {
                alias = prefix.substring(0, colon).trim();
                rest = prefix.substring(colon + 1).trim();
            }
            int hintAt = rest.indexOf('!');
            if (hintAt > 0) {
                return new Header(rest.substring(0, hintAt), rest.substring(hintAt + 1), alias);
            }
            return new Header(rest, null, alias);
        }
    }

    public static SelectResult parse(String select) {
        if (select == null || select.isBlank() || "*".equals(select.trim())) {
            return new SelectResult(List.of(), List.of(), Map.of());
        }

        List<String> columns = new ArrayList<>();
        List<EmbedSpec> embeds = new ArrayList<>();
        Map<String, String> aliases = new HashMap<>();

        for (String part : splitRespectingParens(select)) {
            part = part.trim();
            int parenStart = part.indexOf('(');
            if (parenStart > 0 && part.endsWith(")")) {
                String prefix = part.substring(0, parenStart).trim();
                String innerContent = part.substring(parenStart + 1, part.length() - 1);
                embeds.add(parseEmbedSpec(Header.of(prefix), innerContent));
            } else if (part.contains(":")) {
                String[] aliasParts = part.split(":", 2);
                String alias = aliasParts[0].trim();
                String column = aliasParts[1].trim();
                columns.add(column);
                aliases.put(column, alias);
            } else {
                columns.add(part);
            }
        }

        return new SelectResult(columns, embeds, aliases);
    }

    private static EmbedSpec parseEmbedSpec(Header header, String innerContent) {
        List<String> plainCols = new ArrayList<>();
        List<EmbedSpec> children = new ArrayList<>();
        for (String part : splitRespectingParens(innerContent)) {
            part = part.trim();
            int ip = part.indexOf('(');
            if (ip > 0 && part.endsWith(")")) {
                String childPrefix = part.substring(0, ip).trim();
                String childInner = part.substring(ip + 1, part.length() - 1);
                children.add(parseEmbedSpec(Header.of(childPrefix), childInner));
            } else {
                plainCols.add(part);
            }
        }
        return new EmbedSpec(header.name(), plainCols, header.hint(), children, header.alias());
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
