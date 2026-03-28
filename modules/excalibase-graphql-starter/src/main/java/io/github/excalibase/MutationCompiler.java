package io.github.excalibase;

import graphql.language.Field;
import java.util.Map;

/**
 * Interface for dialect-specific mutation compilation.
 * Postgres uses CTE + RETURNING, MySQL uses two-phase DML + SELECT.
 */
public interface MutationCompiler {
    SqlCompiler.CompiledQuery compileMutation(Field field, String fieldName,
            Map<String, Object> params, Map<String, Object> variables,
            MutationBuilder shared);
}
