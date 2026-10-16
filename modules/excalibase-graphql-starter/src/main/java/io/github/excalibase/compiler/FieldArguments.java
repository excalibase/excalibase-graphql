package io.github.excalibase.compiler;

import graphql.language.Argument;
import graphql.language.Field;

import java.util.Set;

import static io.github.excalibase.schema.GraphqlConstants.ARG_AFTER;
import static io.github.excalibase.schema.GraphqlConstants.ARG_BEFORE;
import static io.github.excalibase.schema.GraphqlConstants.ARG_DISTINCT_ON;
import static io.github.excalibase.schema.GraphqlConstants.ARG_FILTER;
import static io.github.excalibase.schema.GraphqlConstants.ARG_FIRST;
import static io.github.excalibase.schema.GraphqlConstants.ARG_LAST;
import static io.github.excalibase.schema.GraphqlConstants.ARG_LIMIT;
import static io.github.excalibase.schema.GraphqlConstants.ARG_OFFSET;
import static io.github.excalibase.schema.GraphqlConstants.ARG_ORDER_BY;
import static io.github.excalibase.schema.GraphqlConstants.ARG_VECTOR;
import static io.github.excalibase.schema.GraphqlConstants.ARG_WHERE;

/** The arguments each field compiles; any other argument is refused rather than left unapplied. */
public final class FieldArguments {

    public static final Set<String> LIST = Set.of(ARG_WHERE, ARG_FILTER, ARG_ORDER_BY, ARG_LIMIT, ARG_OFFSET,
            ARG_FIRST, ARG_DISTINCT_ON, ARG_VECTOR);
    public static final Set<String> CONNECTION = Set.of(ARG_WHERE, ARG_FILTER, ARG_ORDER_BY, ARG_FIRST, ARG_AFTER,
            ARG_LAST, ARG_BEFORE);
    public static final Set<String> AGGREGATE = Set.of(ARG_WHERE, ARG_FILTER);
    public static final Set<String> NESTED_LIST = Set.of(ARG_WHERE, ARG_FILTER, ARG_ORDER_BY, ARG_LIMIT, ARG_OFFSET);

    private FieldArguments() {
    }

    public static void requireKnown(Field field, Set<String> accepted) {
        for (Argument argument : field.getArguments()) {
            if (!accepted.contains(argument.getName())) {
                throw new IllegalArgumentException("Unknown argument '" + argument.getName() + "' on field '"
                        + field.getName() + "'");
            }
        }
    }
}
