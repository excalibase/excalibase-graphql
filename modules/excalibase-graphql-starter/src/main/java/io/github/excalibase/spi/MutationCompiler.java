package io.github.excalibase.spi;

import graphql.language.Field;
import io.github.excalibase.compiler.MutationBuilder;
import io.github.excalibase.compiler.SqlCompiler;

import java.util.Map;
import java.util.Set;

import static io.github.excalibase.schema.GraphqlConstants.ARG_FILTER;
import static io.github.excalibase.schema.GraphqlConstants.ARG_INPUT;
import static io.github.excalibase.schema.GraphqlConstants.ARG_INPUTS;
import static io.github.excalibase.schema.GraphqlConstants.ARG_WHERE;

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
     * Default: delegates to compileMutation.
     */
    default SqlCompiler.CompiledQuery compileMutationFragment(Field field, String fieldName,
                                                              Map<String, Object> params, Map<String, Object> variables,
                                                              MutationBuilder shared) {
        return compileMutation(field, fieldName, params, variables, shared);
    }

    /**
     * The arguments this compiler applies on a {@code kind} mutation; any other is refused before compiling, so an
     * argument such as {@code onConflict} is never silently ignored. Collection mutations take none by default.
     */
    default Set<String> arguments(MutationKind kind) {
        return switch (kind) {
            case CREATE -> Set.of(ARG_INPUT);
            case CREATE_MANY -> Set.of(ARG_INPUTS);
            case UPDATE -> Set.of(ARG_INPUT, ARG_WHERE, ARG_FILTER);
            case DELETE -> Set.of(ARG_WHERE, ARG_FILTER);
            case UPDATE_COLLECTION, DELETE_FROM_COLLECTION -> Set.of();
        };
    }

    /** True when inserts may nest rows through relationships; only then does the schema offer them. */
    default boolean supportsNestedInserts() {
        return false;
    }
}
