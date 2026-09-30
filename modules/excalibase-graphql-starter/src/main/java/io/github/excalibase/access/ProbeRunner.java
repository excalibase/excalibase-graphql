package io.github.excalibase.access;

import java.util.Map;

/** Runs a {@code SELECT EXISTS (...)} probe against the project's database. */
@FunctionalInterface
public interface ProbeRunner {

    boolean exists(String sql, Map<String, Object> params);
}
