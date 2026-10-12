package io.github.excalibase.config.ws;

import io.github.excalibase.schema.SchemaInfo;

import java.util.Locale;
import java.util.Set;

/**
 * Writes a change the way a GraphQL query reads the row: bigint columns as strings (a query casts them to
 * text, since JSON numbers lose precision past 2^53), so a client compares one value against the other.
 */
final class GraphqlChangeFormat {

    private static final Set<String> BIGINT = Set.of("bigint", "int8");

    private GraphqlChangeFormat() {
    }

    static Object apply(Object rendered, String table, SchemaInfo view) {
        return ChangeImages.mapColumns(rendered, table, view, GraphqlChangeFormat::format);
    }

    private static Object format(Object value, String type) {
        if (value instanceof Number number && type != null && BIGINT.contains(type.toLowerCase(Locale.ROOT))) {
            return number.toString();
        }
        return value;
    }
}
