package io.github.excalibase.config;

import io.github.excalibase.SqlEngineProvider;
import io.github.excalibase.spi.SqlEngineFactory;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

import java.util.Map;
import java.util.ServiceLoader;

/**
 * A slim engine image carries one database plugin. Started against a database it does not carry, the engine refuses
 * at once and names the image to use, rather than starting and failing the first request. Multi-tenant mode has no
 * default database; each project's engine is judged when the project is first served. The data source asks for the check
 * before it builds a pool: a pool would otherwise fail first, on the missing driver, in words that name no image.
 */
@Singleton
public class EngineAvailabilityGuard {

    /** The URL prefixes of the databases an image is published for; a URL of any other database is its own plugin's to judge. */
    private static final Map<String, String> URL_PREFIX_TYPES = Map.of(
            "jdbc:postgresql:", "postgres",
            "jdbc:mysql:", "mysql");

    private final String databaseType;
    private final String datasourceUrl;
    private final boolean multiTenant;

    public EngineAvailabilityGuard(@Value("${app.database-type:postgres}") String databaseType,
                                   @Value("${spring.datasource.url:}") String datasourceUrl,
                                   @Value("${app.security.multi-tenant.provisioning-url:}") String provisioningUrl) {
        this.databaseType = databaseType;
        this.datasourceUrl = datasourceUrl;
        this.multiTenant = provisioningUrl != null && !provisioningUrl.isBlank();
    }

    /** Refuses to start, naming the image to use, when the configured database is not one this build carries. */
    public void check() {
        check(ServiceLoader.load(SqlEngineProvider.class));
    }

    void check(Iterable<SqlEngineProvider> providers) {
        if (multiTenant) {
            return;
        }
        require(databaseType, "APP_DATABASE_TYPE=" + databaseType, providers);
        if (datasourceUrl == null) {
            return;
        }
        URL_PREFIX_TYPES.forEach((prefix, type) -> {
            if (datasourceUrl.startsWith(prefix)) {
                require(type, "SPRING_DATASOURCE_URL=" + prefix + "...", providers);
            }
        });
    }

    private static void require(String type, String setting, Iterable<SqlEngineProvider> providers) {
        try {
            SqlEngineFactory.requireServed(type, providers);
        } catch (IllegalArgumentException refusal) {
            throw new IllegalStateException("Refusing to start with " + setting + ". " + refusal.getMessage(), refusal);
        }
    }
}
