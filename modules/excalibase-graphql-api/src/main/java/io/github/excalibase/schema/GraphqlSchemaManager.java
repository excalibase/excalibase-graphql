package io.github.excalibase.schema;

import io.github.excalibase.SqlDialect;
import io.github.excalibase.cache.TTLCache;
import io.github.excalibase.cdc.NatsCDCService;
import io.github.excalibase.compiler.SqlCompiler;
import io.github.excalibase.config.datasource.DynamicDataSourceManager;
import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.spi.MutationExecutor;
import io.github.excalibase.spi.SchemaLoader;
import io.github.excalibase.spi.SqlEngine;
import io.github.excalibase.spi.SqlEngineFactory;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages database schema introspection, compiler creation, and hot reloads.
 * Produces an immutable {@link EngineState} that the controller snapshots per-request.
 */
@Component
public class GraphqlSchemaManager implements SchemaProvider {

    private static final Logger log = LoggerFactory.getLogger(GraphqlSchemaManager.class);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate txTemplate;
    private final int maxRows;
    private final String databaseType;
    private final int maxQueryDepth;
    private final DynamicDataSourceManager dataSourceManager;

    public record EngineState(SqlCompiler compiler, IntrospectionHandler introspectionHandler,
                              MutationExecutor mutationExecutor) {}

    private volatile EngineState engineState;
    private volatile String defaultSchema;
    private final TTLCache<String, EngineState> tenantEngineStates;

    public GraphqlSchemaManager(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate txTemplate,
            @Value("${app.max-rows:30}") int maxRows,
            @Value("${app.database-type:postgres}") String databaseType,
            @Value("${app.max-query-depth:0}") int maxQueryDepth,
            @Value("${app.cache.schema-ttl-minutes:30}") int schemaTtlMinutes,
            @Autowired(required = false) NatsCDCService natsCDCService,
            @Autowired(required = false) DynamicDataSourceManager dataSourceManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.txTemplate = txTemplate;
        this.maxRows = maxRows;
        this.databaseType = databaseType;
        this.maxQueryDepth = maxQueryDepth;
        this.dataSourceManager = dataSourceManager;
        this.tenantEngineStates = new TTLCache<>(Duration.ofMinutes(schemaTtlMinutes));
        if (natsCDCService != null) {
            natsCDCService.setSchemaReloadCallback(this::reload);
        }
    }

    @PostConstruct
    public void init() {
        SqlEngine engine = SqlEngineFactory.create(databaseType);
        List<String> schemaList = discoverSchemas();

        SchemaInfo schemaInfo = new SchemaInfo();
        try {
            loadMultiSchema(schemaInfo, schemaList, engine.schemaLoader());
        } catch (Exception e) {
            log.warn("Failed to load database schema — starting with empty schema", e);
        }

        this.defaultSchema = resolveDefaultSchema(schemaList, schemaInfo);
        SqlCompiler newCompiler = new SqlCompiler(schemaInfo, defaultSchema, maxRows,
                engine.dialect(), engine.mutationCompiler(), maxQueryDepth);

        IntrospectionHandler newHandler = null;
        try {
            newHandler = new IntrospectionHandler(schemaInfo);
        } catch (Exception e) {
            log.warn("IntrospectionHandler failed to build schema", e);
        }

        MutationExecutor mutationExecutor = SqlEngineFactory.createMutationExecutor(
                databaseType, jdbcTemplate, txTemplate);

        engineState = new EngineState(newCompiler, newHandler, mutationExecutor);
    }

    /**
     * Returns the default EngineState (for non-JWT requests using static datasource).
     * In multi-tenant-only mode (no spring.datasource.url), this throws a clear error
     * instead of returning an empty schema.
     */
    public EngineState getEngineState() {
        return engineState;
    }

    /**
     * Resolve EngineState based on JWT claims.
     * If claims have orgSlug/projectName → tenant-specific state.
     * Otherwise → default state.
     */
    public EngineState resolveEngineState(JwtClaims claims) {
        if (claims != null && claims.orgSlug() != null && claims.projectName() != null) {
            return getEngineState(claims.orgSlug(), claims.projectName());
        }
        return engineState;
    }

    /**
     * Get or build EngineState for a specific tenant.
     * Introspects the tenant's database on first access, then caches.
     */
    public EngineState getEngineState(String orgSlug, String projectName) {
        String tenantKey = orgSlug + "/" + projectName;
        return tenantEngineStates.computeIfAbsent(tenantKey,
            key -> buildTenantEngineState(orgSlug, projectName));
    }

    @Override
    public String getDatabaseType() {
        return databaseType;
    }

    @Override
    public SchemaInfo resolveSchemaInfo(JwtClaims claims) {
        return resolveEngineState(claims).compiler().schemaInfo();
    }

    @Override
    public SqlDialect resolveDialect(JwtClaims claims) {
        return resolveEngineState(claims).compiler().dialect();
    }

    @Override
    public String getDefaultSchema() {
        return defaultSchema;
    }

    /** Reinitialize schema and compiler. Called on DDL events from NatsCDCService. */
    public void reload() {
        EngineState previous = engineState;
        try {
            init();
            log.info("Schema reloaded");
        } catch (Exception e) {
            engineState = previous;
            log.error("Schema reload failed — retaining previous state", e);
        }
    }

    /**
     * Pick the default schema: first schema that has tables, falling back to "public".
     */
    private String resolveDefaultSchema(List<String> schemas, SchemaInfo schemaInfo) {
        for (String schema : schemas) {
            for (String tableKey : schemaInfo.getTableNames()) {
                if (tableKey.startsWith(schema + ".")) {
                    return schema;
                }
            }
        }
        return schemas.isEmpty() ? "public" : schemas.getFirst();
    }

    /**
     * Auto-discover all non-system schemas. Unlike PostgREST which uses a static
     * db-schemas config, we auto-discover because we serve multiple tenants — each
     * tenant's database may have different schemas. A static list doesn't work
     * in multi-tenant mode. REST clients use Accept-Profile header to select schema.
     */
    private List<String> discoverSchemas() {
        return discoverSchemas(jdbcTemplate);
    }

    private List<String> discoverSchemas(JdbcTemplate jdbc) {
        try {
            String sql = "mysql".equalsIgnoreCase(databaseType)
                    ? "SELECT schema_name FROM information_schema.schemata " +
                      "WHERE schema_name NOT IN ('information_schema', 'performance_schema', 'mysql', 'sys') " +
                      "ORDER BY schema_name"
                    : "SELECT schema_name FROM information_schema.schemata " +
                      "WHERE schema_name NOT LIKE 'pg_%' " +
                      "AND schema_name != 'information_schema' " +
                      "ORDER BY schema_name";
            return jdbc.queryForList(sql, String.class);
        } catch (Exception e) {
            log.warn("Failed to discover schemas — falling back to 'public'", e);
            return List.of("public");
        }
    }

    private void loadMultiSchema(SchemaInfo schemaInfo, List<String> schemaList, SchemaLoader loader) {
        loadMultiSchema(schemaInfo, schemaList, loader, jdbcTemplate);
    }

    private String findCompoundKey(SchemaInfo schemaInfo, String rawTable, List<String> allSchemas) {
        return findCompoundKey(schemaInfo, rawTable, allSchemas, null);
    }

    private String findCompoundKey(SchemaInfo schemaInfo, String rawTable, List<String> allSchemas, String preferredSchema) {
        if (preferredSchema != null) {
            String candidate = preferredSchema + "." + rawTable;
            if (schemaInfo.hasTable(candidate)) return candidate;
        }
        for (String s : allSchemas) {
            if (s.equals(preferredSchema)) continue;
            String candidate = s + "." + rawTable;
            if (schemaInfo.hasTable(candidate)) return candidate;
        }
        return null;
    }

    private EngineState buildTenantEngineState(String orgSlug, String projectName) {
        if (dataSourceManager == null) {
            throw new IllegalStateException("Multi-tenant not enabled — DynamicDataSourceManager is null");
        }
        DataSource tenantDs = dataSourceManager.getDataSource(orgSlug, projectName);
        JdbcTemplate tenantJdbc = new JdbcTemplate(tenantDs);

        SqlEngine engine = SqlEngineFactory.create(databaseType);
        List<String> tenantSchemas = discoverSchemas(tenantJdbc);

        SchemaInfo schemaInfo = new SchemaInfo();
        loadMultiSchema(schemaInfo, tenantSchemas, engine.schemaLoader(), tenantJdbc);

        String defaultSchema = resolveDefaultSchema(tenantSchemas, schemaInfo);
        SqlCompiler compiler = new SqlCompiler(schemaInfo, defaultSchema, maxRows,
                engine.dialect(), engine.mutationCompiler(), maxQueryDepth);

        IntrospectionHandler handler = null;
        try {
            handler = new IntrospectionHandler(schemaInfo);
        } catch (Exception e) {
            log.warn("IntrospectionHandler failed for tenant {}/{}", orgSlug, projectName, e);
        }

        TransactionTemplate tenantTx = new TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(tenantDs));
        MutationExecutor mutationExecutor = SqlEngineFactory.createMutationExecutor(
                databaseType, tenantJdbc, tenantTx);

        log.info("built_tenant_engine tenant={}/{} tables={}",
                orgSlug, projectName, schemaInfo.getTableNames().size());
        return new EngineState(compiler, handler, mutationExecutor);
    }

    private void loadMultiSchema(SchemaInfo schemaInfo, List<String> schemaList,
                                 SchemaLoader loader, JdbcTemplate jdbc) {
        Map<String, SchemaInfo> perSchema = new LinkedHashMap<>();
        loader.loadAll(jdbc, schemaList, perSchema);

        for (var schemaEntry : perSchema.entrySet()) {
            String schema = schemaEntry.getKey();
            SchemaInfo temp = schemaEntry.getValue();
            mergeTablesAndColumns(schemaInfo, schema, temp);
            mergeEnumsProcsAndComposites(schemaInfo, schema, temp);
            // Extensions are global to the database, so every per-schema temp
            // carries the same set. Copying once onto the target is enough —
            // duplicates are harmless since addExtension uses a map put.
            for (var ext : temp.getExtensions().entrySet()) {
                schemaInfo.addExtension(ext.getKey(), ext.getValue());
            }
        }
        mergeForeignKeys(schemaInfo, perSchema, schemaList);
    }

    private void mergeTablesAndColumns(SchemaInfo target, String schema, SchemaInfo source) {
        for (String table : source.getTableNames()) {
            String key = schema + "." + table;
            for (String col : source.getColumns(table)) {
                target.addColumn(key, col, source.getColumnType(table, col));
                String enumType = source.getEnumType(table, col);
                if (enumType != null) {
                    target.addColumnEnumType(key, col, schema + "." + enumType);
                }
            }
            target.setTableSchema(key, schema);
            if (source.hasPrimaryKey(table)) {
                for (String pk : source.getPrimaryKeys(table)) {
                    target.addPrimaryKey(key, pk);
                }
            }
            if (source.isView(table)) {
                target.addView(key);
            }
            var computed = source.getComputedFields(table);
            if (computed != null) {
                for (var cf : computed) {
                    target.addComputedField(key, cf.functionName(), cf.returnType());
                }
            }
        }
    }

    private void mergeEnumsProcsAndComposites(SchemaInfo target, String schema, SchemaInfo source) {
        for (var entry : source.getEnumTypes().entrySet()) {
            for (String label : entry.getValue()) {
                target.addEnumValue(schema + "." + entry.getKey(), label);
            }
        }
        for (var entry : source.getStoredProcedures().entrySet()) {
            target.addStoredProcedure(schema + "." + entry.getKey(), entry.getValue());
        }
        for (var entry : source.getCompositeTypes().entrySet()) {
            for (var field : entry.getValue()) {
                target.addCompositeTypeField(schema + "." + entry.getKey(), field.name(), field.type());
            }
        }
    }

    private void mergeForeignKeys(SchemaInfo target, Map<String, SchemaInfo> perSchema, List<String> schemaList) {
        for (var schemaEntry : perSchema.entrySet()) {
            String schema = schemaEntry.getKey();
            SchemaInfo temp = schemaEntry.getValue();
            for (var entry : temp.getAllForwardFks().entrySet()) {
                String fkKey = entry.getKey();
                String fromTable = fkKey.substring(0, fkKey.lastIndexOf('.'));
                SchemaInfo.FkInfo fk = entry.getValue();
                String compoundFrom = schema + "." + fromTable;
                String compoundTo = findCompoundKey(target, fk.refTable(), schemaList, schema);
                if (compoundTo != null && target.hasTable(compoundFrom)) {
                    if (fk.isComposite()) {
                        target.addCompositeForeignKey(compoundFrom, fk.fkColumns(), compoundTo, fk.refColumns());
                    } else {
                        target.addForeignKey(compoundFrom, fk.fkColumn(), compoundTo, fk.refColumn());
                    }
                }
            }
        }
    }
}
