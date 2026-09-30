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
     * What the caller may do with the tables of the schema they were served. Unrestricted by default,
     * for a provider serving a deployment without permissions.
     */
    default TableAccess resolveAccess(Principal principal) {
        return TableAccess.UNRESTRICTED;
    }

    /** The tracked functions the caller may call; none by default, so nothing untracked is reachable. */
    default ExposedFunctions resolveFunctions(Principal principal) {
        return ExposedFunctions.NONE;
    }

    String getDatabaseType();

    /**
     * The schema a request without {@code Accept-Profile} addresses: the default
     * schema of the database serving the caller's project, never of another one.
     */
    String resolveDefaultSchema(Principal principal);
}
