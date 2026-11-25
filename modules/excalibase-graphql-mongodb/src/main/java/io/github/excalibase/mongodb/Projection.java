package io.github.excalibase.mongodb;

import java.util.Set;

/**
 * What a query returns.
 *
 * @param select fields (GraphQL names, {@code id} included) read as they are stored
 * @param nulled fields the caller's column rules mask: returned as null whatever is stored
 */
record Projection(Set<String> select, Set<String> nulled) {
    Projection {
        select = Set.copyOf(select);
        nulled = Set.copyOf(nulled);
    }
}
