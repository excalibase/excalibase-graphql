package io.github.excalibase.schema;

import io.github.excalibase.SqlDialect;
import io.github.excalibase.cache.TTLCache;
import io.github.excalibase.cdc.NatsCDCService;
import io.github.excalibase.compiler.SqlCompiler;
import io.github.excalibase.config.datasource.DynamicDataSourceManager;
import io.github.excalibase.config.datasource.TenantContext;
import io.github.excalibase.access.AccessPlan;
import io.github.excalibase.access.ProbeRunner;
import io.github.excalibase.permissions.PermissionProvider;
import io.github.excalibase.permissions.PermissionSet;
import io.github.excalibase.permissions.PermissionsUnavailableException;
import io.github.excalibase.permissions.RolePermissions;
import io.github.excalibase.rls.ProjectCacheEvictor;
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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reflects each project's database once and serves every role an engine built from it and the role's
 * access plan (docs/features/permissions.md §7). This is the one place a plan is built: the compiler,
 * introspection, the request guards and realtime all take theirs from the {@link EngineState} made here.
 */
@Component
public class GraphqlSchemaManager implements SchemaProvider, AccessPlans, ProjectCacheEvictor {

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
    private final PermissionProvider permissionProvider;
    private final boolean jwtEnabled;

    /**
     * One role's engine on one project.
     *
     * @param permissionsVersion the permission document it was built from, or {@link #NO_DOCUMENT}
     */
    public record EngineState(SqlCompiler compiler, IntrospectionHandler introspectionHandler,
                              MutationExecutor mutationExecutor, AccessPlan plan, String defaultSchema,
                              ProbeRunner probes, long permissionsVersion) {}

    /** One project's database as reflected, shared by the engines of all its roles. */
    record Reflection(SchemaInfo schema, String defaultSchema, SqlEngine engine,
                      MutationExecutor mutationExecutor, ProbeRunner probes) {}

    /** The role is part of the key because the schema is: each role is served its own. */
    record EngineKey(String projectId, String role) {}

    static final long NO_DOCUMENT = -1;

    private final AtomicReference<EngineState> engineState = new AtomicReference<>();
    private final AtomicReference<Reflection> defaultReflection = new AtomicReference<>();
    private final TTLCache<String, Reflection> reflections;
    private final TTLCache<EngineKey, EngineState> tenantEngineStates;
    private final ThreadLocal<RequestEngine> requestEngine = new ThreadLocal<>();

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
            @Autowired(required = false) PermissionProvider permissionProvider,
            @Value("${app.security.jwt-enabled:true}") boolean jwtEnabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.txTemplate = txTemplate;
        this.maxRows = maxRows;
        this.databaseType = databaseType;
        this.maxQueryDepth = maxQueryDepth;
        this.reservedSchemas = ReservedSchemas.fromConfig(reservedSchemasConfig);
        this.dataSourceManager = dataSourceManager;
        this.permissionProvider = permissionProvider;
        this.jwtEnabled = jwtEnabled;
        this.reflections = new TTLCache<>(Duration.ofMinutes(schemaTtlMinutes));
        this.tenantEngineStates = new TTLCache<>(Duration.ofMinutes(schemaTtlMinutes));
        requireSupportedPermissions();
        if (natsCDCService != null) {
            natsCDCService.setSchemaReloadCallback(this::reload);
        }
    }

    /**
     * Permission filters compile to Postgres SQL only, so a MySQL deployment with a permission source
     * is refused at startup rather than served unfiltered. Without a source, authentication on means
     * only the service role reaches tables.
     */
    private void requireSupportedPermissions() {
        boolean sourced = permissionProvider != null && permissionProvider.configured();
        if (jwtEnabled && sourced && "mysql".equalsIgnoreCase(databaseType)) {
            throw new IllegalStateException("Permissions are Postgres-only: a MySQL deployment cannot use "
                    + "app.security.rls.policy-url. Unset it, or serve this database with Postgres.");
        }
        if (jwtEnabled && !sourced) {
            log.warn("permissions_source_missing: app.security.rls.policy-url is blank, so only the service "
                    + "role is served tables; every other role gets an empty schema");
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
        String defaultSchema = resolveDefaultSchema(schemaList, schemaInfo);
        MutationExecutor mutationExecutor = SqlEngineFactory.createMutationExecutor(
                databaseType, jdbcTemplate, txTemplate);
        Reflection reflection = new Reflection(schemaInfo, defaultSchema, engine, mutationExecutor,
                probesOn(jdbcTemplate));
        defaultReflection.set(reflection);
        // Requests without a project get the configured database whole: there is no project to hold
        // permissions for. Every project-scoped request is built per role below.
        engineState.set(assemble(reflection, AccessPlan.allAccess(schemaInfo), NO_DOCUMENT));
        reflections.clear();
        tenantEngineStates.clear();
    }

    /**
     * The single place an {@link EngineState} is assembled. The compiler and the introspection handler
     * are both built from the plan's view, so what the role cannot reach has no field anywhere.
     */
    EngineState assemble(Reflection reflection, AccessPlan plan, long permissionsVersion) {
        SchemaInfo view = plan.view();
        SqlCompiler compiler = new SqlCompiler(view, reflection.defaultSchema(), maxRows,
                reflection.engine().dialect(), reflection.engine().mutationCompiler(), maxQueryDepth, plan.access(),
                plan.functions());
        IntrospectionHandler handler = null;
        try {
            handler = new IntrospectionHandler(view, plan.access(), plan.functions());
        } catch (Exception e) {
            log.warn("IntrospectionHandler failed to build schema", e);
        }
        return new EngineState(compiler, handler, reflection.mutationExecutor(), plan, reflection.defaultSchema(),
                reflection.probes(), permissionsVersion);
    }

    /**
     * Returns the default EngineState (for requests that carry no project). In multi-tenant-only mode
     * (no spring.datasource.url) there is none.
     */
    public EngineState getEngineState() {
        return engineState.get();
    }

    /**
     * Resolve EngineState for the current request. The project comes from {@link TenantContext} — the
     * URL path, already checked against the projects this deployment serves — and never from the
     * token. The role is the one the request runs as, resolved once by the authentication filter.
     */
    public EngineState resolveEngineState(Principal principal) {
        String projectId = TenantContext.getTenantId();
        RequestEngine resolved = requestEngine.get();
        if (resolved != null && resolved.principal() == principal && Objects.equals(resolved.projectId(), projectId)) {
            return resolved.state();
        }
        String orgSlug = TenantContext.getOrgSlug();
        if (orgSlug == null && principal != null && principal.claims() != null) {
            orgSlug = principal.claims().orgSlug();
        }
        EngineState state = resolveEngineState(orgSlug, projectId, principal);
        if (principal != null) {
            requestEngine.set(new RequestEngine(principal, projectId, state));
        }
        return state;
    }

    /**
     * Ends the request's reuse of its resolved engine. The authentication filter resolves it first; the
     * controllers then read that same engine rather than asking the permission source again.
     */
    @Override
    public void requestFinished() {
        requestEngine.remove();
    }

    /**
     * The engine one caller is served on {@code projectId}: built from the project's reflection and the
     * role's plan, and rebuilt when the project's permission document moves to a new version.
     *
     * @throws io.github.excalibase.permissions.PermissionsUnavailableException the permissions cannot be read
     */
    public EngineState resolveEngineState(String orgSlug, String projectId, Principal principal) {
        if (projectId == null) {
            return engineState.get();
        }
        String role = engineRole(principal);
        Reflection reflection = reflections.computeIfAbsent(projectId, key -> reflect(orgSlug, projectId));
        EngineKey key = new EngineKey(projectId, role);
        if (!permissionsSourced()) {
            return tenantEngineStates.computeIfAbsent(key, ignored -> build(reflection, projectId, role, null));
        }
        PermissionSet permissions = Principal.SERVICE.equals(role)
                ? servicePermissions(projectId) : permissionProvider.permissionsFor(projectId);
        long version = permissions == null ? NO_DOCUMENT : permissions.version();
        EngineState cached = tenantEngineStates.get(key);
        if (cached != null && cached.permissionsVersion() == version) {
            return cached;
        }
        EngineState built = build(reflection, projectId, role, permissions);
        tenantEngineStates.put(key, built);
        return built;
    }

    /**
     * {@code service} needs the document only for the project's tracked functions. When it cannot be
     * read, service is still served every table, and no function: nothing counts as tracked.
     */
    private PermissionSet servicePermissions(String projectId) {
        try {
            return permissionProvider.permissionsFor(projectId);
        } catch (PermissionsUnavailableException e) {
            log.warn("service_functions_unavailable project={}: serving tables only", projectId);
            return null;
        }
    }

    /**
     * {@code service} is served every table and every valid tracked function. Without a permission
     * source, every other role is served nothing — unless authentication is off, where the whole
     * schema is served — and no function is tracked.
     */
    private EngineState build(Reflection reflection, String projectId, String role, PermissionSet permissions) {
        AccessPlan plan;
        if (Principal.SERVICE.equals(role)) {
            plan = AccessPlan.allAccess(reflection.schema(), permissions == null ? List.of() : permissions.functions());
        } else if (permissions == null) {
            plan = AccessPlan.forRole(reflection.schema(), new RolePermissions(role, Map.of(), List.of(), Set.of()));
        } else {
            plan = AccessPlan.forRole(reflection.schema(), permissions.forRole(role));
        }
        EngineState state = assemble(reflection, plan, permissions == null ? NO_DOCUMENT : permissions.version());
        log.info("built_engine project={} role={} tables={} served_tables={} functions={}", projectId, role,
                reflection.schema().getTableNames().size(), plan.view().getTableNames().size(),
                plan.functions().all().size());
        return state;
    }

    private boolean permissionsSourced() {
        return permissionProvider != null && permissionProvider.configured();
    }

    /**
     * The role an engine is built for. With authentication off there is no principal and the whole
     * schema is served; with it on, a missing principal is a bug, and guessing a role would hide it.
     */
    private String engineRole(Principal principal) {
        if (!jwtEnabled) {
            return Principal.SERVICE;
        }
        if (principal == null) {
            throw new IllegalStateException("No principal for a request on a deployment where permissions apply");
        }
        return principal.role();
    }

    /**
     * With tenant routing wired a project is served from its own database only; otherwise this is
     * single-database mode, where the one project the configured database serves has already been
     * matched to the path.
     */
    Reflection reflect(String orgSlug, String projectId) {
        return dataSourceManager != null ? reflectTenant(orgSlug, projectId) : defaultReflection.get();
    }

    /** The engine one request resolved, reused for the rest of that request on this thread. */
    private record RequestEngine(Principal principal, String projectId, EngineState state) {}

    @Override
    public AccessPlan planFor(Principal principal) {
        EngineState state = resolveEngineState(principal);
        return state == null ? null : state.plan();
    }

    @Override
    public Optional<RealtimeAccess> realtime(String orgSlug, String projectId, Principal principal,
                                             String subscriptionKey) {
        EngineState state = resolveEngineState(orgSlug, projectId, principal);
        if (state == null) {
            return Optional.empty();
        }
        return tableFor(state, subscriptionKey).flatMap(table ->
                state.plan().changes(table, sessionVariables(principal, projectId))
                        .map(filter -> new RealtimeAccess(table, filter, state.probes())));
    }

    private static Map<String, String> sessionVariables(Principal principal, String projectId) {
        return principal == null ? Map.of() : principal.sessionVariables(projectId);
    }

    /** {@code schema_table}, {@code schema.table}, or a bare table of the default schema, among the served tables. */
    private static Optional<String> tableFor(EngineState state, String subscriptionKey) {
        SchemaInfo view = state.plan().view();
        String qualified = state.defaultSchema() + "." + subscriptionKey;
        if (view.hasTable(subscriptionKey) || view.hasTable(qualified)) {
            return Optional.of(view.hasTable(subscriptionKey) ? subscriptionKey : qualified);
        }
        return view.getTableNames().stream()
                .filter(table -> table.replace('.', '_').equals(subscriptionKey))
                .findFirst();
    }

    /**
     * Drops every cached engine of {@code projectId}, all roles included, and its reflection: a
     * permission or schema change reshapes the schema of some roles, and guessing which would leave a
     * stale, over-wide one behind.
     */
    public void evict(String projectId) {
        if (projectId == null) {
            return;
        }
        reflections.remove(projectId);
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

    @Override
    public TableAccess resolveAccess(Principal principal) {
        return requireEngineState(principal).plan().access();
    }

    @Override
    public ExposedFunctions resolveFunctions(Principal principal) {
        return requireEngineState(principal).plan().functions();
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

    Reflection reflectTenant(String orgSlug, String projectId) {
        DataSource tenantDs = dataSourceManager.getDataSource(orgSlug, projectId);
        JdbcTemplate tenantJdbc = new JdbcTemplate(tenantDs);

        SqlEngine engine = SqlEngineFactory.create(databaseType);
        List<String> tenantSchemas = discoverSchemas(tenantJdbc);

        SchemaInfo schemaInfo = new SchemaInfo();
        loadMultiSchema(schemaInfo, tenantSchemas, engine.schemaLoader(), tenantJdbc);

        TransactionTemplate tenantTx = new TransactionTemplate(new DataSourceTransactionManager(tenantDs));
        MutationExecutor mutationExecutor = SqlEngineFactory.createMutationExecutor(
                databaseType, tenantJdbc, tenantTx);
        log.info("reflected_tenant tenant={}/{} tables={}", orgSlug, projectId, schemaInfo.getTableNames().size());
        return new Reflection(schemaInfo, resolveDefaultSchema(tenantSchemas, schemaInfo), engine, mutationExecutor,
                probesOn(tenantJdbc));
    }

    /** Realtime's database probes run on the project's own database. */
    private static ProbeRunner probesOn(JdbcTemplate jdbc) {
        if (jdbc == null) {
            return (sql, params) -> false;
        }
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(jdbc);
        return (sql, params) -> Boolean.TRUE.equals(named.queryForObject(sql, params, Boolean.class));
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
        for (var entry : source.getFunctions().entrySet()) {
            target.addFunction(schema + "." + entry.getKey(), entry.getValue());
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
