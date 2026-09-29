package io.github.excalibase.schema;

import io.github.excalibase.SqlDialect;
import io.github.excalibase.security.Principal;

/**
 * Provides SchemaInfo and SqlDialect for the current request context.
 * Implemented by GraphqlSchemaManager in the API module.
 * Used by both GraphQL and REST controllers. Every method takes the {@link Principal}
 * the request runs as, because the schema served depends on its role.
 */
public interface SchemaProvider {

    SchemaInfo resolveSchemaInfo(Principal principal);

    SqlDialect resolveDialect(Principal principal);

    /**
     * Which operations the caller may run on the tables of the schema they were
     * served. Unrestricted by default so a provider that does not implement
     * exposure filtering keeps serving its whole schema.
     */
    default TableExposure resolveExposure(Principal principal) {
        return TableExposure.UNRESTRICTED;
    }

    String getDatabaseType();

    /**
     * The schema a request without {@code Accept-Profile} addresses: the default
     * schema of the database serving the caller's project, never of another one.
     */
    String resolveDefaultSchema(Principal principal);
}
