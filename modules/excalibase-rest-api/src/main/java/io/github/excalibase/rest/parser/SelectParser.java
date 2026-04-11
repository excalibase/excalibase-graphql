package io.github.excalibase.rest.parser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SelectParser {

    private SelectParser() {}

    public record SelectResult(List<String> columns, List<EmbedSpec> embeds, Map<String, String> aliases) {}
    public record EmbedSpec(String relationName, List<String> columns, String fkHint, List<EmbedSpec> children) {
        public EmbedSpec(String relationName, List<String> columns) {
            this(relationName, columns, null, List.of());
        }
        public EmbedSpec(String relationName, List<String> columns, String fkHint) {
            this(relationName, columns, fkHint, List.of());
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
                String fkHint = null;
                String relationName = prefix;
                int bangIdx = prefix.indexOf('!');
                if (bangIdx > 0) {
                    relationName = prefix.substring(0, bangIdx);
                    fkHint = prefix.substring(bangIdx + 1);
                }
                embeds.add(parseEmbedSpec(relationName, innerContent, fkHint));
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

    private static EmbedSpec parseEmbedSpec(String name, String innerContent, String fkHint) {
        List<String> plainCols = new ArrayList<>();
        List<EmbedSpec> children = new ArrayList<>();
        for (String part : splitRespectingParens(innerContent)) {
            part = part.trim();
            int ip = part.indexOf('(');
            if (ip > 0 && part.endsWith(")")) {
                String childPrefix = part.substring(0, ip).trim();
                String childInner = part.substring(ip + 1, part.length() - 1);
                String childFkHint = null;
                String childName = childPrefix;
                int cBang = childPrefix.indexOf('!');
                if (cBang > 0) {
                    childName = childPrefix.substring(0, cBang);
                    childFkHint = childPrefix.substring(cBang + 1);
                }
                children.add(parseEmbedSpec(childName, childInner, childFkHint));
            } else {
                plainCols.add(part);
            }
        }
        return new EmbedSpec(name, plainCols, fkHint, children);
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
