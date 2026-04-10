package io.github.excalibase.rest.parser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SelectParser {

    private SelectParser() {}

    public record SelectResult(List<String> columns, List<EmbedSpec> embeds, Map<String, String> aliases) {}
    public record EmbedSpec(String relationName, List<String> columns, String fkHint) {
        public EmbedSpec(String relationName, List<String> columns) {
            this(relationName, columns, null);
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
                String innerCols = part.substring(parenStart + 1, part.length() - 1);
                String fkHint = null;
                String relationName = prefix;
                int bangIdx = prefix.indexOf('!');
                if (bangIdx > 0) {
                    relationName = prefix.substring(0, bangIdx);
                    fkHint = prefix.substring(bangIdx + 1);
                }
                embeds.add(new EmbedSpec(relationName,
                    List.of(innerCols.split(",")).stream().map(String::trim).toList(), fkHint));
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

    private static List<String> splitRespectingParens(String input) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
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
