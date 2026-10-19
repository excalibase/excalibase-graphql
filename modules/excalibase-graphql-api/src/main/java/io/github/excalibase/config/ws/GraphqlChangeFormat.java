package io.github.excalibase.config.ws;

import io.github.excalibase.schema.SchemaInfo;


/** Writes a change the way a GraphQL query reads the row: bigint and decimal columns as strings. */
final class GraphqlChangeFormat {

    private GraphqlChangeFormat() {
    }

    static Object apply(Object rendered, String table, SchemaInfo view) {
        return ChangeImages.mapColumns(rendered, table, view, ExactChangeValues::format);
    }
}
