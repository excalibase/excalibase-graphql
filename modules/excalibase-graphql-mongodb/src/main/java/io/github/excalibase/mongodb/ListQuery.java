package io.github.excalibase.mongodb;

import java.util.List;

/** What a list request asks for beyond the caller's own permissions: its filter, order and page. */
record ListQuery(Cond filter, List<SortKey> order, int limit, int offset) {
    ListQuery {
        order = List.copyOf(order);
    }
}
