package io.github.excalibase.spi;

import io.github.excalibase.rls.UserContext;

import java.util.Map;

/**
 * Answers a GraphQL request for a database the core does not speak SQL to. The engine owns its whole
 * request path (compile, enforce permissions, execute), so nothing SQL-shaped crosses this boundary.
 */
public interface QueryEngine extends AutoCloseable {

    /**
     * @param caller who is asking; row and column rules are decided from it
     * @return a GraphQL response body: {@code data} and, on failure, {@code errors}
     */
    Map<String, Object> execute(String query, Map<String, Object> variables, UserContext caller);

    @Override
    void close();
}
