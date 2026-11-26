package io.github.excalibase.document;

import io.github.excalibase.rls.UserContext;
import io.github.excalibase.spi.QueryEngine;
import io.github.excalibase.spi.QueryEngineProvider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** A document database that keeps no data and counts its engines, so a test can see one replaced and closed. */
public final class CountingProvider implements QueryEngineProvider {

    public static final String TYPE = "countingdb";
    static final AtomicInteger OPEN = new AtomicInteger();
    public static final AtomicBoolean HEALTHY = new AtomicBoolean(true);
    public static final AtomicInteger INSTANTIATED = new AtomicInteger();
    static final AtomicInteger BUILT = new AtomicInteger();

    /** A ServiceLoader scan builds one of these, so the count shows whether a caller scans. */
    public CountingProvider() {
        INSTANTIATED.incrementAndGet();
    }

    @Override
    public List<String> supportedTypes() {
        return List.of(TYPE);
    }

    @Override
    public QueryEngine create(Map<String, Object> settings) {
        BUILT.incrementAndGet();
        OPEN.incrementAndGet();
        return new QueryEngine() {
            @Override
            public Map<String, Object> execute(String query, Map<String, Object> variables, UserContext caller) {
                return Map.of("data", Map.of());
            }

            @Override
            public boolean healthy() {
                return HEALTHY.get();
            }

            @Override
            public void close() {
                OPEN.decrementAndGet();
            }
        };
    }
}
