package io.github.excalibase.spi;

import io.github.excalibase.SqlDialect;

/**
 * Aggregates database-specific components for a given database type.
 * Created by SqlEngineFactory.
 */
public record SqlEngine(
        SqlDialect dialect,
        SchemaLoader schemaLoader,
        MutationCompiler mutationCompiler
) {}
