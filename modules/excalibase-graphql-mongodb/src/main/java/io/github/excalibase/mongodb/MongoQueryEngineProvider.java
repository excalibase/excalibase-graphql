package io.github.excalibase.mongodb;

import io.github.excalibase.rls.ColumnPolicy;
import io.github.excalibase.rls.Policy;
import io.github.excalibase.spi.QueryEngine;
import io.github.excalibase.spi.QueryEngineProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLContext;

/**
 * Serves MongoDB, and DocumentDB, which speaks its wire protocol. Settings:
 * {@code uri}, {@code database}, {@code collections} (declared fields, see {@link Catalog}),
 * {@code policies} / {@code columnPolicies} (the shared rls-core models), {@code openCollections}
 * (names, or {@code "*"}, served without row rules), and the limits {@code maxRows},
 * {@code maxFields} and {@code maxTimeMs}, and an optional {@code sslContext} for a private CA.
 */
public final class MongoQueryEngineProvider implements QueryEngineProvider {

    private static final int DEFAULT_MAX_ROWS = 30;
    private static final int DEFAULT_MAX_FIELDS = 100;
    private static final long DEFAULT_MAX_TIME_MS = 5_000;

    @Override
    public List<String> supportedTypes() {
        return List.of("mongodb", "documentdb");
    }

    @Override
    public QueryEngine create(Map<String, Object> settings) {
        String uri = required(settings, "uri");
        String database = required(settings, "database");
        Catalog catalog = Catalog.fromSettings(settings);
        RowSecurity rows = new RowSecurity(catalog, typed(settings, "policies", Policy.class));
        ColumnSecurity columns = new ColumnSecurity(catalog, typed(settings, "columnPolicies", ColumnPolicy.class));
        requireGoverned(catalog, rows, openCollections(catalog, settings));

        PipelineRunner runner = new DriverPipelineRunner(uri, database,
                number(settings, "maxTimeMs", DEFAULT_MAX_TIME_MS), sslContext(settings));
        try {
            return new MongoQueryEngine(catalog, rows, columns, runner, MongoCapabilities.STANDARD,
                    (int) number(settings, "maxRows", DEFAULT_MAX_ROWS),
                    (int) number(settings, "maxFields", DEFAULT_MAX_FIELDS));
        } catch (RuntimeException e) {
            runner.close();
            throw e;
        }
    }

    /** Every collection is either governed by an ALLOW policy or named open: nothing is served by omission. */
    private static void requireGoverned(Catalog catalog, RowSecurity rows, Set<String> open) {
        List<String> ungoverned = catalog.all().stream().map(CollectionDef::name)
                .filter(name -> !rows.governs(name) && !open.contains(name) && !open.contains("*")).sorted().toList();
        if (!ungoverned.isEmpty()) {
            throw new IllegalStateException("Collections " + ungoverned + " have no ALLOW policy and are not listed "
                    + "in openCollections: add policies for them, or name them open on purpose");
        }
    }

    private static Set<String> openCollections(Catalog catalog, Map<String, Object> settings) {
        Set<String> open = Set.copyOf(typed(settings, "openCollections", String.class));
        for (String name : open) {
            if (!"*".equals(name) && catalog.collection(name).isEmpty()) {
                throw new IllegalStateException("openCollections names \"" + name + "\", which is not a declared collection");
            }
        }
        return open;
    }

    /** The wiring may hand over the trust for a database with a private CA; the URI alone cannot carry it. */
    private static SSLContext sslContext(Map<String, Object> settings) {
        Object value = settings.get("sslContext");
        if (value == null) {
            return null;
        }
        if (value instanceof SSLContext context) {
            return context;
        }
        throw new IllegalStateException("Setting \"sslContext\" must be a javax.net.ssl.SSLContext");
    }

    private static String required(Map<String, Object> settings, String key) {
        Object value = settings.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalStateException("Setting \"" + key + "\" is required");
        }
        return value.toString();
    }

    private static long number(Map<String, Object> settings, String key, long fallback) {
        Object value = settings.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number && number.longValue() > 0) {
            return number.longValue();
        }
        throw new IllegalStateException("Setting \"" + key + "\" must be a positive number");
    }

    private static <T> List<T> typed(Map<String, Object> settings, String key, Class<T> type) {
        Object value = settings.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> items)) {
            throw new IllegalStateException("Setting \"" + key + "\" must be a list");
        }
        List<T> converted = new ArrayList<>();
        for (Object item : items) {
            if (!type.isInstance(item)) {
                throw new IllegalStateException("Setting \"" + key + "\" holds something that is not a " + type.getSimpleName());
            }
            converted.add(type.cast(item));
        }
        return converted;
    }
}
