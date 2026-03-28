package io.github.excalibase;

/**
 * Aggregates database-specific components for a given database type.
 * Created by SqlEngineFactory.
 */
public record SqlEngine(
        SqlDialect dialect,
        SchemaLoader schemaLoader,
        MutationCompiler mutationCompiler
) {}
