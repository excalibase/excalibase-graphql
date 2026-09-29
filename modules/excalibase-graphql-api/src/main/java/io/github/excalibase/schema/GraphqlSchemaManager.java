package io.github.excalibase.schema;

import io.github.excalibase.SqlDialect;
import io.github.excalibase.cache.TTLCache;
import io.github.excalibase.cdc.NatsCDCService;
import io.github.excalibase.compiler.SqlCompiler;
import io.github.excalibase.config.datasource.DynamicDataSourceManager;
import io.github.excalibase.config.datasource.TenantContext;
import io.github.excalibase.rls.ExposureFilter;
import io.github.excalibase.rls.PolicyProvider;
import io.github.excalibase.rls.ProjectCacheEvictor;
import io.github.excalibase.rls.TableGrants;
import io.github.excalibase.security.Principal;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages database schema introspection, compiler creation, and hot reloads.
 * Produces an immutable {@link EngineState} that the controller snapshots per-request.
 */
@Component
public class GraphqlSchemaManager implements SchemaProvider, ExposureSource, ProjectCacheEvictor {

    private static final Logger log = LoggerFactory.getLogger(GraphqlSchemaManager.class);

    /** Default query-depth limit applied when {@code app.max-query-depth} is unset. */
    public static final int DEFAULT_MAX_QUERY_DEPTH = 15;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate txTemplate;
    private final int maxRows;
    private final String databaseType;
    private final int maxQueryDepth;
    private final ReservedSchemas reservedSchemas;
    private final DynamicDataSourceManager dataSourceManager;

    private final PolicyProvider policyProvider;

    public record EngineState(SqlCompiler compiler, IntrospectionHandler introspectionHandler,
                              MutationExecutor mutationExecutor, TableExposure exposure,
                              String defaultSchema) {}

    /**
     * Cache identity for a built engine. The role is part of it because the schema
     * is: two callers on the same project with different roles are served different
     * sets of tables, so one cached state cannot stand for both.
     */
    record EngineKey(String projectId, String role) {}

    private final AtomicReference<EngineState> engineState = new AtomicReference<>();
    /** The unfiltered reflection of the configured database, re-filtered per caller. */
    private final AtomicReference<SchemaInfo> defaultSchemaInfo = new AtomicReference<>();
    private volatile String defaultSchema;
    private final TTLCache<EngineKey, EngineState> tenantEngineStates;

    public GraphqlSchemaManager(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate txTemplate,
            @Value("${app.max-rows:30}") int maxRows,
            @Value("${app.database-type:postgres}") String databaseType,
            @Value("${app.max-query-depth:#{T(io.github.excalibase.schema.GraphqlSchemaManager).DEFAULT_MAX_QUERY_DEPTH}}") int maxQueryDepth,
            @Value("${app.cache.schema-ttl-minutes:30}") int schemaTtlMinutes,
            @Value("${app.reserved-schemas:}") String reservedSchemasConfig,
            @Autowired(required = false) NatsCDCService natsCDCService,
            @Autowired(required = false) DynamicDataSourceManager dataSourceManager,
            @Autowired(required = false) PolicyProvider policyProvider) {
        this.policyProvider = policyProvider;
        this.jdbcTemplate = jdbcTemplate;
        this.txTemplate = txTemplate;
        this.maxRows = maxRows;
        this.databaseType = databaseType;
        this.maxQueryDepth = maxQueryDepth;
        this.reservedSchemas = ReservedSchemas.fromConfig(reservedSchemasConfig);
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
        this.defaultSchemaInfo.set(schemaInfo);

        this.defaultSchema = resolveDefaultSchema(schemaList, schemaInfo);
        MutationExecutor mutationExecutor = SqlEngineFactory.createMutationExecutor(
                databaseType, jdbcTemplate, txTemplate);

        // The unscoped state serves requests that carry no project, so there is no
        // grant configuration to read: it is the whole schema. Project-scoped
        // callers get a filtered view built from this same database below.
        engineState.set(assemble(schemaInfo, defaultSchema, engine, mutationExecutor,
                TableGrants.unenforced(null), null));
        tenantEngineStates.clear();
    }

    /**
     * The single place an {@link EngineState} is assembled, and therefore the single
     * place the exposure filter is applied. The compiler and the introspection
     * handler are both built from the <em>filtered</em> schema, so a resource the
     * caller was not granted has no field to ask for anywhere downstream — there is
     * no per-call-site check because there is nothing left to check.
     */
    private EngineState assemble(SchemaInfo schemaInfo, String schemaForCompiler, SqlEngine engine,
                                 MutationExecutor mutationExecutor, TableGrants grants, String callerRole) {
        ExposureFilter.Result exposed = ExposureFilter.apply(schemaInfo, grants, callerRole);
        SqlCompiler compiler = new SqlCompiler(exposed.schemaInfo(), schemaForCompiler, maxRows,
                engine.dialect(), engine.mutationCompiler(), maxQueryDepth, exposed.exposure());

        IntrospectionHandler handler = null;
        try {
            handler = new IntrospectionHandler(exposed.schemaInfo(), exposed.exposure());
        } catch (Exception e) {
            log.warn("IntrospectionHandler failed to build schema", e);
        }
        return new EngineState(compiler, handler, mutationExecutor, exposed.exposure(), schemaForCompiler);
    }

    /**
     * The grants in force for a project and role. A project with no exposure
     * configuration, a deployment with no policy source wired, and the {@code service}
     * role (which bypasses permissions) are all unenforced.
     */
    private TableGrants grantsFor(String projectId, String callerRole) {
        if (policyProvider == null || projectId == null || Principal.SERVICE.equals(callerRole)) {
            return TableGrants.unenforced(projectId);
        }
        return policyProvider.tableGrantsFor(projectId);
    }

    /**
     * Returns the default EngineState (for non-JWT requests using static datasource).
     * In multi-tenant-only mode (no spring.datasource.url), this throws a clear error
     * instead of returning an empty schema.
     */
    public EngineState getEngineState() {
        return engineState.get();
    }

    /**
     * Resolve EngineState for the current request.
     *
     * <p>The project comes from {@link TenantContext} — the URL path, already checked
     * against the projects this deployment serves — and never from the token. The
     * role is the one the request runs as, resolved once by the authentication filter.
     */
    public EngineState resolveEngineState(Principal principal) {
        String projectId = TenantContext.getTenantId();
        String orgSlug = TenantContext.getOrgSlug();
        if (orgSlug == null && principal != null && principal.claims() != null) {
            orgSlug = principal.claims().orgSlug();
        }
        return resolveEngineState(orgSlug, projectId, principal);
    }

    /**
     * Get or build the EngineState for one caller: the tenant's database when
     * multi-tenant routing is wired, otherwise the configured database, in both
     * cases filtered to what the principal's role was granted on {@code projectId}.
     */
    public EngineState resolveEngineState(String orgSlug, String projectId, Principal principal) {
        if (projectId == null) {
            return engineState.get();
        }
        String callerRole = cacheRole(principal);
        return tenantEngineStates.computeIfAbsent(new EngineKey(projectId, callerRole),
                key -> buildEngineState(orgSlug, projectId, callerRole));
    }

    /**
     * The role an engine is built and cached for. {@code service} (the bypass) gets the
     * whole schema. No principal is legitimate only where no permission source is wired
     * (authentication off); anywhere else it is a bug, and guessing a role would hide it.
     */
    private String cacheRole(Principal principal) {
        if (principal != null) {
            return principal.role();
        }
        if (policyProvider != null) {
            throw new IllegalStateException("No principal for a request on a deployment where permissions apply");
        }
        return Principal.SERVICE;
    }

    /**
     * With tenant routing wired a project is served from its own database only, for
     * anonymous callers too; otherwise this is single-database mode, where the one
     * project the configured database serves has already been matched to the path.
     */
    EngineState buildEngineState(String orgSlug, String projectId, String callerRole) {
        return dataSourceManager != null
                ? buildTenantEngineState(orgSlug, projectId, callerRole)
                : filteredDefaultEngineState(projectId, callerRole);
    }

    /** The exposure in force for {@code principal} on the current request. */
    @Override
    public TableExposure resolveExposure(Principal principal) {
        EngineState state = resolveEngineState(principal);
        return state == null ? TableExposure.UNRESTRICTED : state.exposure();
    }

    @Override
    public TableExposure exposureFor(String orgSlug, String projectId, Principal principal) {
        EngineState state = resolveEngineState(orgSlug, projectId, principal);
        return state == null ? TableExposure.UNRESTRICTED : state.exposure();
    }

    @Override
    public boolean requiresPrincipal() {
        return policyProvider != null;
    }

    /**
     * Drops every cached engine state for {@code projectId}, all roles included: a
     * grant change reshapes the schema for one role and leaves the others alone, and
     * guessing which is which would leave a stale, over-wide schema behind.
     */
    public void evict(String projectId) {
        if (projectId == null) {
            return;
        }
        tenantEngineStates.removeIf(key -> projectId.equals(key.projectId()));
    }

    @Override
    public String getDatabaseType() {
        return databaseType;
    }

    @Override
    public SchemaInfo resolveSchemaInfo(Principal principal) {
        return requireEngineState(principal).compiler().schemaInfo();
    }

    @Override
    public SqlDialect resolveDialect(Principal principal) {
        return requireEngineState(principal).compiler().dialect();
    }

    /**
     * The caller's engine state, or a clear failure. In multi-tenant-only mode
     * there is no default state, so an unscoped request resolves to null; saying
     * so beats a NullPointerException from the dereference that follows.
     */
    private EngineState requireEngineState(Principal principal) {
        EngineState state = resolveEngineState(principal);
        if (state == null) {
            throw new IllegalStateException(
                    "No schema for this request: multi-tenant mode is configured with no default "
                            + "datasource, so the request must carry a project");
        }
        return state;
    }

    @Override
    public String resolveDefaultSchema(Principal principal) {
        return requireEngineState(principal).defaultSchema();
    }

    /** Reinitialize schema and compiler. Called on DDL events from NatsCDCService. */
    public void reload() {
        EngineState previous = engineState.get();
        try {
            init();
            log.info("Schema reloaded");
        } catch (Exception e) {
            engineState.set(previous);
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
     * Platform-owned schemas are removed here, at the single discovery layer, so no
     * downstream consumer (type generation, REST, introspection, realtime) can see them.
     */
    private List<String> discoverSchemas() {
        return discoverSchemas(jdbcTemplate);
    }

    List<String> discoverSchemas(JdbcTemplate jdbc) {
        try {
            String sql = "mysql".equalsIgnoreCase(databaseType)
                    ? "SELECT schema_name FROM information_schema.schemata " +
                      "WHERE schema_name NOT IN ('information_schema', 'performance_schema', 'mysql', 'sys') " +
                      "ORDER BY schema_name"
                    : "SELECT schema_name FROM information_schema.schemata " +
                      "WHERE schema_name NOT LIKE 'pg_%' " +
                      "AND schema_name != 'information_schema' " +
                      "ORDER BY schema_name";
            return reservedSchemas.filter(jdbc.queryForList(sql, String.class));
        } catch (Exception e) {
            log.warn("Failed to discover schemas — falling back to 'public'", e);
            return List.of("public");
        }
    }

    private void loadMultiSchema(SchemaInfo schemaInfo, List<String> schemaList, SchemaLoader loader) {
        loadMultiSchema(schemaInfo, schemaList, loader, jdbcTemplate);
    }

    private String findCompoundKey(SchemaInfo schemaInfo, String rawTable, List<String> allSchemas, String preferredSchema) {
        if (preferredSchema != null) {
            String candidate = preferredSchema + "." + rawTable;
            if (schemaInfo.hasTable(candidate)) return candidate;
        }
        for (String schema : allSchemas) {
            if (schema.equals(preferredSchema)) continue;
            String candidate = schema + "." + rawTable;
            if (schemaInfo.hasTable(candidate)) return candidate;
        }
        return null;
    }

    /**
     * The configured database for its one pinned project, filtered to the caller's
     * grants. Single-database mode only: {@code KnownProjectFilter} has refused
     * every other project before this is reached.
     */
    private EngineState filteredDefaultEngineState(String projectId, String callerRole) {
        EngineState base = engineState.get();
        SchemaInfo source = defaultSchemaInfo.get();
        TableGrants grants = grantsFor(projectId, callerRole);
        if (base == null || source == null || !grants.enforced()) {
            return base;
        }
        return assemble(source, defaultSchema, SqlEngineFactory.create(databaseType),
                base.mutationExecutor(), grants, callerRole);
    }

    EngineState buildTenantEngineState(String orgSlug, String projectId, String callerRole) {
        if (dataSourceManager == null) {
            throw new IllegalStateException("Multi-tenant not enabled — DynamicDataSourceManager is null");
        }
        DataSource tenantDs = dataSourceManager.getDataSource(orgSlug, projectId);
        JdbcTemplate tenantJdbc = new JdbcTemplate(tenantDs);

        SqlEngine engine = SqlEngineFactory.create(databaseType);
        List<String> tenantSchemas = discoverSchemas(tenantJdbc);

        SchemaInfo schemaInfo = new SchemaInfo();
        loadMultiSchema(schemaInfo, tenantSchemas, engine.schemaLoader(), tenantJdbc);

        String tenantDefaultSchema = resolveDefaultSchema(tenantSchemas, schemaInfo);

        TransactionTemplate tenantTx = new TransactionTemplate(
                new DataSourceTransactionManager(tenantDs));
        MutationExecutor mutationExecutor = SqlEngineFactory.createMutationExecutor(
                databaseType, tenantJdbc, tenantTx);

        EngineState state = assemble(schemaInfo, tenantDefaultSchema, engine, mutationExecutor,
                grantsFor(projectId, callerRole), callerRole);

        log.info("built_tenant_engine tenant={}/{} role={} tables={} exposed_tables={}",
                orgSlug, projectId, callerRole, schemaInfo.getTableNames().size(),
                state.compiler().schemaInfo().getTableNames().size());
        return state;
    }

    private void loadMultiSchema(SchemaInfo schemaInfo, List<String> schemaList,
                                 SchemaLoader loader, JdbcTemplate jdbc) {
        Map<String, SchemaInfo> perSchema = new LinkedHashMap<>();
        loader.loadAll(jdbc, schemaList, perSchema);

        SchemaMerger merger = new SchemaMerger();
        for (var schemaEntry : perSchema.entrySet()) {
            String schema = schemaEntry.getKey();
            SchemaInfo temp = schemaEntry.getValue();
            merger.merge(schemaInfo, schema, temp);
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
