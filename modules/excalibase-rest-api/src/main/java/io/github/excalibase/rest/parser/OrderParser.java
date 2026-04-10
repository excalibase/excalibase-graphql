package io.github.excalibase.rest.parser;

import java.util.ArrayList;
import java.util.List;

public final class OrderParser {

    private OrderParser() {}

    public record OrderSpec(String column, String direction, String nulls) {}

    public static List<OrderSpec> parse(String order) {
        if (order == null || order.isBlank()) return List.of();

        List<OrderSpec> specs = new ArrayList<>();
        for (String part : order.split(",")) {
            String[] segments = part.trim().split("\\.");
            String column = segments[0];
            String direction = "ASC";
            String nulls = null;

            if (segments.length >= 2) {
                direction = switch (segments[1].toLowerCase()) {
                    case "desc" -> "DESC";
                    default -> "ASC";
                };
            }
            if (segments.length >= 3) {
                nulls = switch (segments[2].toLowerCase()) {
                    case "nullsfirst" -> "NULLS FIRST";
                    case "nullslast" -> "NULLS LAST";
                    default -> null;
                };
            }
            specs.add(new OrderSpec(column, direction, nulls));
        }
        return specs;
    }
}
