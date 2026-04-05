package io.github.excalibase.config.datasource;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * Deferred-connection DataSource backed by AbstractRoutingDataSource.
 * Starts with no targets — the app boots even if no database is reachable.
 * Targets are registered at init time (or hot-swapped on schema reload).
 */
public class DynamicRoutingDataSource extends AbstractRoutingDataSource {

    public static final String DEFAULT_KEY = "default";

    @Override
    protected Object determineCurrentLookupKey() {
        return DEFAULT_KEY;
    }
}
