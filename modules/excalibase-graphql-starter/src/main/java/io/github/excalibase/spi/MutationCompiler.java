package io.github.excalibase.spi;

import graphql.language.Field;
import io.github.excalibase.compiler.MutationBuilder;
import io.github.excalibase.compiler.SqlCompiler;

import java.util.Map;

/**
 * Interface for dialect-specific mutation compilation.
 * Postgres uses CTE + RETURNING, MySQL uses two-phase DML + SELECT.
 */
public interface MutationCompiler {
    SqlCompiler.CompiledQuery compileMutation(Field field, String fieldName,
                                              Map<String, Object> params, Map<String, Object> variables,
                                              MutationBuilder shared);

    /**
     * Returns the raw CTE SQL fragment (before wrapping in jsonb_build_object for the field name).
     * Used by SqlCompiler to combine multiple mutations into a single transaction.
     * Default: delegates to compileMutation (MySQL two-phase path is unchanged).
     */
    default SqlCompiler.CompiledQuery compileMutationFragment(Field field, String fieldName,
                                                              Map<String, Object> params, Map<String, Object> variables,
                                                              MutationBuilder shared) {
        return compileMutation(field, fieldName, params, variables, shared);
    }
}
