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

    String getDatabaseType();
}
