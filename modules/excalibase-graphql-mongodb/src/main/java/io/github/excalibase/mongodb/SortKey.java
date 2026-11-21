package io.github.excalibase.mongodb;

/** One ordering term: a GraphQL-side field name and its direction. */
record SortKey(String field, boolean ascending) {}
