package io.github.excalibase.rest.controller;

import io.github.excalibase.schema.SchemaProvider;
import io.github.excalibase.security.Principal;

/** The schema a request addresses through its profile header. */
final class RequestedSchema {

    private static final String IDENT_REGEX = "^[A-Za-z_]\\w{0,62}$";

    private RequestedSchema() {
    }

    /**
     * The schema named by {@code header}, or the caller's default schema when there is none. A schema the engine
     * never serves resolves to null, which the caller answers as it does a table that does not exist.
     */
    static String resolve(SchemaProvider schemaProvider, String header, Principal principal) {
        if (header == null || header.isBlank()) {
            return schemaProvider.resolveDefaultSchema(principal);
        }
        String trimmed = header.trim();
        if (!trimmed.matches(IDENT_REGEX)) {
            throw new IllegalArgumentException("Invalid Content-Profile");
        }
        return schemaProvider.isReserved(trimmed) ? null : trimmed;
    }
}
