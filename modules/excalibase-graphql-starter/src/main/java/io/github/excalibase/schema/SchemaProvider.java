package io.github.excalibase.schema;

import io.github.excalibase.SqlDialect;
import io.github.excalibase.security.JwtClaims;

/**
 * Provides SchemaInfo and SqlDialect for the current request context.
 * Implemented by GraphqlSchemaManager in the API module.
 * Used by both GraphQL and REST controllers.
 */
public interface SchemaProvider {

    SchemaInfo resolveSchemaInfo(JwtClaims claims);

    SqlDialect resolveDialect(JwtClaims claims);

    /**
     * Which operations the caller may run on the tables of the schema they were
     * served. Unrestricted by default so a provider that does not implement
     * exposure filtering keeps serving its whole schema.
     */
    default TableExposure resolveExposure(JwtClaims claims) {
        return TableExposure.UNRESTRICTED;
    }

    String getDatabaseType();

    /** Returns the default schema name (first discovered or configured schema). */
    String getDefaultSchema();
}
