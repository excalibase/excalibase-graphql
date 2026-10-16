package io.github.excalibase.security;

import java.util.List;
import java.util.Map;

/**
 * The write half of a request's permissions, consulted by the mutation compilers through
 * {@link RlsContext}: the values a permission presets, and the check every written row must pass.
 */
public interface WriteGuard {

    /** Column to bind value for the columns the permission presets on {@code op}; the client may not set them. */
    Map<String, Object> presets(String tableName, RlsOp op);

    /**
     * The permission's check over rows aliased {@code alias}, or null when every row passes. The
     * compiler evaluates it on the written rows in the same statement and fails it with
     * {@link PermissionCheckFailedException} when any row does not pass.
     */
    RlsWhereContributor.Contribution check(String tableName, String alias, RlsOp op);

    /**
     * The columns identifying a row of {@code tableName} in the database, whether or not the role may read
     * them: a database whose writes cannot return their rows reads them back by it. Empty when unknown.
     */
    default List<String> rowKey(String tableName) {
        return List.of();
    }

    /** True when the row key is one column the database fills itself when an insert leaves it out. */
    default boolean keyGenerated(String tableName) {
        return false;
    }
}
