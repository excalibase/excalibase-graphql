package io.github.excalibase.spi;

import java.util.Optional;

import static io.github.excalibase.schema.GraphqlConstants.COLLECTION_SUFFIX;
import static io.github.excalibase.schema.GraphqlConstants.CREATE_MANY_PREFIX;
import static io.github.excalibase.schema.GraphqlConstants.CREATE_PREFIX;
import static io.github.excalibase.schema.GraphqlConstants.DELETE_FROM_PREFIX;
import static io.github.excalibase.schema.GraphqlConstants.DELETE_PREFIX;
import static io.github.excalibase.schema.GraphqlConstants.UPDATE_PREFIX;

/** The table mutations a field name can route to, matched in the compilers' routing order. */
public enum MutationKind {
    UPDATE_COLLECTION,
    DELETE_FROM_COLLECTION,
    CREATE_MANY,
    CREATE,
    UPDATE,
    DELETE;

    public static Optional<MutationKind> of(String fieldName) {
        boolean collection = fieldName.endsWith(COLLECTION_SUFFIX);
        if (collection && fieldName.startsWith(UPDATE_PREFIX)) return Optional.of(UPDATE_COLLECTION);
        if (collection && fieldName.startsWith(DELETE_FROM_PREFIX)) return Optional.of(DELETE_FROM_COLLECTION);
        if (fieldName.startsWith(CREATE_MANY_PREFIX)) return Optional.of(CREATE_MANY);
        if (fieldName.startsWith(CREATE_PREFIX)) return Optional.of(CREATE);
        if (fieldName.startsWith(UPDATE_PREFIX)) return Optional.of(UPDATE);
        if (fieldName.startsWith(DELETE_PREFIX)) return Optional.of(DELETE);
        return Optional.empty();
    }
}
