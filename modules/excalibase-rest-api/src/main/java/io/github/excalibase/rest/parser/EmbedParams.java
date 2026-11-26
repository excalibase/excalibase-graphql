package io.github.excalibase.rest.parser;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The {@code order} and {@code limit} an embed takes as {@code embed.order=} and {@code embed.limit=}, nested as {@code a.b.limit=}. */
public final class EmbedParams {

    private static final String ORDER = ".order";
    private static final String LIMIT = ".limit";

    private EmbedParams() {}

    /** Every embed's path: its key (alias or name), under its parents' keys, joined by dots. */
    public static Set<String> paths(List<SelectParser.EmbedSpec> embeds) {
        Set<String> paths = new LinkedHashSet<>();
        collect("", embeds, paths);
        return paths;
    }

    private static void collect(String parent, List<SelectParser.EmbedSpec> embeds, Set<String> paths) {
        for (SelectParser.EmbedSpec embed : embeds) {
            String path = parent + (embed.alias() != null ? embed.alias() : embed.relationName());
            paths.add(path);
            collect(path + ".", embed.children(), paths);
        }
    }

    /** True for {@code <embed path>.order} and {@code <embed path>.limit}: they steer an embed, they filter nothing. */
    public static boolean isEmbedParam(String param, Set<String> embedPaths) {
        return embedPaths.contains(withoutSuffix(param, ORDER)) || embedPaths.contains(withoutSuffix(param, LIMIT));
    }

    private static String withoutSuffix(String param, String suffix) {
        return param.endsWith(suffix) ? param.substring(0, param.length() - suffix.length()) : null;
    }

    public static List<OrderParser.OrderSpec> order(String path, Map<String, List<String>> params) {
        List<String> values = params.get(path + ORDER);
        return values == null || values.isEmpty() ? List.of() : OrderParser.parse(values.get(0));
    }

    /** The embed's row limit, or null when none was asked; a negative or non-numeric one is refused. */
    public static Integer limit(String path, Map<String, List<String>> params) {
        List<String> values = params.get(path + LIMIT);
        if (values == null || values.isEmpty() || values.get(0).isBlank()) return null;
        try {
            int parsed = Integer.parseInt(values.get(0).trim());
            if (parsed >= 0) return parsed;
        } catch (NumberFormatException _) {
            // refused below with the parameter named, never the parser's message
        }
        throw new IllegalArgumentException("Invalid " + path + LIMIT + ": must be a non-negative integer");
    }
}
