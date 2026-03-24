package io.github.excalibase.service;

import io.github.excalibase.cache.TTLCache;
import io.github.excalibase.config.AppConfig;
import io.github.excalibase.model.TableInfo;
import io.github.excalibase.schema.reflector.IDatabaseSchemaReflector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

/**
 * Service to reflect the full database schema once using superuser privileges.
 * Part of the Root + Filter approach for efficient role-based schema generation.
 *
 * This service maintains a single "golden" schema that contains all tables and columns,
 * which is then filtered based on role privileges instead of re-reflecting for each role.
 *
 * <p>The reflector is resolved lazily via {@link ServiceLookup} so that multiple
 * DB-specific implementations can coexist on the classpath without ambiguity.</p>
 */
@Service
public class FullSchemaService {
    private static final Logger log = LoggerFactory.getLogger(FullSchemaService.class);
    private static final String FULL_SCHEMA_KEY = "full_schema";

    private final ServiceLookup serviceLookup;
    private final AppConfig appConfig;
    private final TTLCache<String, Map<String, TableInfo>> schemaCache;
    private IDatabaseSchemaReflector schemaReflector;

    public FullSchemaService(ServiceLookup serviceLookup, AppConfig appConfig) {
        this.serviceLookup = serviceLookup;
        this.appConfig = appConfig;
        this.schemaCache = new TTLCache<>(Duration.ofMinutes(appConfig.getCache().getSchemaTtlMinutes()));
    }

    private IDatabaseSchemaReflector getReflector() {
        if (schemaReflector == null) {
            schemaReflector = serviceLookup.forBean(IDatabaseSchemaReflector.class,
                    appConfig.getDatabaseType().getName());
        }
        return schemaReflector;
    }

    /**
     * Gets the complete database schema using superuser privileges.
     * This is cached and used as the "source of truth" for filtering.
     * 
     * Cost: ~300ms once per TTL duration vs ~300ms per role per request.
     */
    public Map<String, TableInfo> getFullSchema() {
        return schemaCache.computeIfAbsent(FULL_SCHEMA_KEY, key -> {
            log.info("Reflecting full database schema...");
            try {
                Map<String, TableInfo> fullSchema = getReflector().reflectSchema();
                log.debug("Schema reflection completed: {} tables/views", fullSchema.size());
                return fullSchema;
            } catch (Exception e) {
                log.error("Failed to reflect full database schema: {}", e.getMessage(), e);
                throw new RuntimeException("Unable to reflect database schema", e);
            }
        });
    }

    /**
     * Force refresh the full schema cache.
     * Useful when database structure changes.
     */
    public Map<String, TableInfo> refreshFullSchema() {
        log.info("Force refreshing full database schema...");
        schemaCache.remove(FULL_SCHEMA_KEY);
        return getFullSchema();
    }

    /**
     * Check if schema is cached.
     */
    public boolean isSchemaLoaded() {
        return schemaCache.get(FULL_SCHEMA_KEY) != null;
    }

    /**
     * Clear the schema cache.
     */
    public void clearCache() {
        schemaCache.clear();
        log.info("Full schema cache cleared");
    }

    /**
     * Get schema cache statistics.
     */
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = Map.of(
            "isLoaded", isSchemaLoaded(),
            "cacheSize", schemaCache.size(),
            "cacheInfo", "Full database schema cache"
        );
        
        if (isSchemaLoaded()) {
            Map<String, TableInfo> schema = getFullSchema();
            stats = Map.of(
                "isLoaded", true,
                "cacheSize", schemaCache.size(),
                "tableCount", schema.size(),
                "totalColumns", schema.values().stream()
                    .mapToInt(table -> table.getColumns().size())
                    .sum(),
                "cacheInfo", "Full database schema cache"
            );
        }
        
        return stats;
    }

    /**
     * Get summary of the full schema for debugging.
     */
    public Map<String, Object> getSchemaSummary() {
        if (!isSchemaLoaded()) {
            return Map.of("error", "Schema not loaded yet");
        }
        
        Map<String, TableInfo> schema = getFullSchema();
        return Map.of(
            "tableCount", schema.size(),
            "tableNames", schema.keySet(),
            "totalColumns", schema.values().stream()
                .mapToInt(table -> table.getColumns().size())
                .sum(),
            "averageColumnsPerTable", schema.values().stream()
                .mapToInt(table -> table.getColumns().size())
                .average()
                .orElse(0.0)
        );
    }
} 