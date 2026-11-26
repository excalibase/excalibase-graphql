package io.github.excalibase.schema;

import io.github.excalibase.DialectFeature;
import io.github.excalibase.SqlDialect;
import io.github.excalibase.cache.TTLCache;
import io.github.excalibase.cdc.NatsCDCService;
import io.github.excalibase.compiler.SqlCompiler;
import io.github.excalibase.config.datasource.DynamicDataSourceManager;
import io.github.excalibase.config.datasource.TenantContext;
import io.github.excalibase.config.datasource.TenantDatabase;
import io.github.excalibase.access.AccessPlan;
import io.github.excalibase.access.ProbeRunner;
import io.github.excalibase.permissions.compile.PermissionDialect;
import io.github.excalibase.permissions.PermissionProvider;
import io.github.excalibase.permissions.PermissionSet;
import io.github.excalibase.permissions.PermissionsUnavailableException;
import io.github.excalibase.permissions.RolePermissions;
import io.github.excalibase.rls.ProjectCacheEvictor;
import io.github.excalibase.security.Principal;
import io.github.excalibase.spi.MutationExecutor;
import io.github.excalibase.spi.QueryEngineFactory;
import io.github.excalibase.spi.SchemaLoader;
import io.github.excalibase.spi.SqlEngine;
import io.github.excalibase.spi.SqlEngineFactory;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Value;
import io.github.excalibase.config.JwtEnabledSetting;
import jakarta.inject.Inject;
import io.micronaut.core.annotation.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import io.micronaut.core.propagation.MutablePropagatedContext;
import io.micronaut.core.propagation.PropagatedContext;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reflects each project's database once and serves every role an engine built from it and the role's
 * access plan (docs/features/permissions.md §7). This is the one place a plan is built: the compiler,
 * introspection, the request guards and realtime all take theirs from the {@link EngineState} made here.
 */
@Context
public class GraphqlSchemaManager implements SchemaProvider, AccessPlans, ProjectCacheEvictor {

    private static final Logger log = LoggerFactory.getLogger(GraphqlSchemaManager.class);

    /** Default query-depth limit applied when {@code app.max-query-depth} is unset. */
    public static final int DEFAULT_MAX_QUERY_DEPTH = 15;

    private static final String MYSQL = "mysql";
    private static final int FINGERPRINT_TIMEOUT_SECONDS = 5;
    private static final int RELOAD_TIMEOUT_SECONDS = 60;

    private final JdbcTemplate jdbcTemplate;
    private final int maxRows;
    private final boolean capNestedLists;
    private final String databaseType;
    private final boolean documentMode;
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

    /**
     * One project's database as reflected, shared by the engines of all its roles.
     *
     * @param databaseType the engine the database runs, as {@link SqlEngineFactory} names it
     */
    record Reflection(SchemaInfo schema, String defaultSchema, String databaseType, SqlEngine engine,
                      MutationExecutor mutationExecutor, ProbeRunner probes) {}

    /** The role is part of the key because the schema is: each role is served its own. */
    record EngineKey(String projectId, String role) {}

    static final long NO_DOCUMENT = -1;

    private final AtomicReference<EngineState> engineState = new AtomicReference<>();
    private final AtomicReference<Reflection> defaultReflection = new AtomicReference<>();
    /** The catalog fingerprint taken just before the default database was last reflected; null when unknown. */
    private final AtomicReference<String> reflectedFingerprint = new AtomicReference<>();
    /** Odd while the default reflection is being replaced, and higher after each replacement; see {@link #publish}. */
    private final AtomicLong generation = new AtomicLong();
    private final TTLCache<String, Reflection> reflections;
    private final TTLCache<EngineKey, EngineState> tenantEngineStates;

    @Inject
    public GraphqlSchemaManager(
            JdbcTemplate jdbcTemplate,
            @Value("${app.max-rows:30}") int maxRows,
            @Value("${app.database-type:postgres}") String databaseType,
            @Value("${app.max-query-depth:15}") int maxQueryDepth,
            @Value("${app.cache.schema-ttl-minutes:30}") int schemaTtlMinutes,
            @Value("${app.reserved-schemas:}") String reservedSchemasConfig,
            @Nullable NatsCDCService natsCDCService,
            @Nullable DynamicDataSourceManager dataSourceManager,
            @Nullable PermissionProvider permissionProvider,
            JwtEnabledSetting jwtEnabledSetting,
            @Value("${app.cap-nested-lists:false}") boolean capNestedLists) {
        this(jdbcTemplate, maxRows, databaseType, maxQueryDepth, schemaTtlMinutes, reservedSchemasConfig,
                natsCDCService, dataSourceManager, permissionProvider, jwtEnabledSetting.enabled(), capNestedLists);
    }

    /** For code that already holds the parsed setting; the engine itself is built with {@link JwtEnabledSetting}. */
    GraphqlSchemaManager(
            JdbcTemplate jdbcTemplate,
            int maxRows,
            String databaseType,
            int maxQueryDepth,
            int schemaTtlMinutes,
            String reservedSchemasConfig,
            NatsCDCService natsCDCService,
            DynamicDataSourceManager dataSourceManager,
            PermissionProvider permissionProvider,
            boolean jwtEnabled) {
        this(jdbcTemplate, maxRows, databaseType, maxQueryDepth, schemaTtlMinutes, reservedSchemasConfig,
                natsCDCService, dataSourceManager, permissionProvider, jwtEnabled, false);
    }

    GraphqlSchemaManager(
            JdbcTemplate jdbcTemplate,
            int maxRows,
            String databaseType,
            int maxQueryDepth,
            int schemaTtlMinutes,
            String reservedSchemasConfig,
            NatsCDCService natsCDCService,
            DynamicDataSourceManager dataSourceManager,
            PermissionProvider permissionProvider,
            boolean jwtEnabled,
            boolean capNestedLists) {
        this.capNestedLists = capNestedLists;
        this.jdbcTemplate = jdbcTemplate;
        this.maxRows = maxRows;
        this.databaseType = databaseType;
        this.documentMode = QueryEngineFactory.serves(databaseType);
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
     * The same pool with a bound on each query, so a stuck catalog lock cannot hang the caller for good. A
     * template with no data source of its own is used as it is.
     */
    private JdbcTemplate bounded(int timeoutSeconds) {
        DataSource dataSource = jdbcTemplate.getDataSource();
        if (dataSource == null) {
            return jdbcTemplate;
        }
        JdbcTemplate bounded = new JdbcTemplate(dataSource);
        bounded.setQueryTimeout(timeoutSeconds);
        return bounded;
    }

    /**
     * Permissions compile to the database's own SQL (Postgres and MySQL), so a database the compiler does
     * not speak is refused at startup rather than served unfiltered. Without a source, authentication on
     * means only the service role reaches tables.
     */
    private void requireSupportedPermissions() {
        if (documentMode()) {
            return; // a document database enforces the permission document itself, not as SQL
        }
        boolean sourced = permissionProvider != null && permissionProvider.configured();
        if (jwtEnabled && sourced) {
            try {
                SqlEngineFactory.create(databaseType).dialect().permissionDialect();
            } catch (IllegalArgumentException | UnsupportedOperationException e) {
                throw new IllegalStateException("Permissions cannot be compiled for database type " + databaseType
                        + ", so app.security.rls.policy-url cannot be used with it", e);
            }
        }
        if (jwtEnabled && !sourced) {
            log.warn("permissions_source_missing: app.security.rls.policy-url is blank, so only the service "
                    + "role is served tables; every other role gets an empty schema");
        }
    }

    /** True when the configured database is a document database: it has no SQL schema, plan or compiler. */
    public boolean documentMode() {
        return documentMode;
    }

    /** Reflects the configured database at start. One that cannot be read is served as an empty schema. */
    @PostConstruct
    public void init() {
        if (documentMode()) {
            log.info("Document database ({}): no SQL schema is reflected", databaseType);
            return;
        }
        SqlEngine engine = SqlEngineFactory.create(databaseType);
        // Taken before the load, so DDL that lands while it runs is seen by the next poll.
        String fingerprintBeforeLoad = fingerprintOrNull(engine);
        try {
            publish(loadDefault(engine), fingerprintBeforeLoad);
        } catch (Exception e) {
            log.warn("Failed to load database schema — starting with empty schema", e);
            // No fingerprint, so the next poll tries again.
            publish(new Reflection(new SchemaInfo(), "public", databaseType, engine,
                    SqlEngineFactory.createMutationExecutor(databaseType), probesOn(jdbcTemplate)), null);
        }
    }

    /** The configured database as it is now; throws when it cannot be read. */
    Reflection loadDefault(SqlEngine engine) {
        SchemaInfo schemaInfo = new SchemaInfo();
        JdbcTemplate reading = bounded(RELOAD_TIMEOUT_SECONDS);
        List<String> schemaList = discoverSchemas(reading, databaseType);
        loadMultiSchema(schemaInfo, schemaList, engine.schemaLoader(), reading);
        return new Reflection(schemaInfo, resolveDefaultSchema(schemaList, schemaInfo), databaseType, engine,
                SqlEngineFactory.createMutationExecutor(databaseType), probesOn(jdbcTemplate));
    }

    /** A seam for tests: runs after the new reflection is set and before the caches are emptied. */
    void betweenReflectionAndCacheClear() {
        // nothing in production
    }

    /** A seam for tests: runs after the caches are emptied and before the generation is even again. */
    void afterCacheClear() {
        // nothing in production
    }

    /**
     * Makes {@code reflection} the one served. Requests without a project get the configured database
     * whole: there is no project to hold permissions for. Every project-scoped request is built per role.
     * The generation is odd while this runs and moves on when it is done (a sequence lock), so a request that
     * began before, or during, the publish can tell when it is done that what it cached may be stale. There is
     * one writer at a time, which {@code synchronized} keeps true: the sequence lock needs it.
     */
    synchronized void publish(Reflection reflection, String fingerprint) {
        EngineState state = assemble(reflection, AccessPlan.allAccess(
                reflection.engine().dialect().permissionDialect(), reflection.schema()), NO_DOCUMENT);
        generation.incrementAndGet();   // odd: a publish is under way
        defaultReflection.set(reflection);
        reflectedFingerprint.set(fingerprint);
        engineState.set(state);
        betweenReflectionAndCacheClear();
        reflections.clear();
        tenantEngineStates.clear();
        afterCacheClear();
        generation.incrementAndGet();   // even again: the caches hold nothing of the old reflection
    }

    /**
     * The single place an {@link EngineState} is assembled. The compiler and the introspection handler
     * are both built from the plan's view, so what the role cannot reach has no field anywhere.
     */
    EngineState assemble(Reflection reflection, AccessPlan plan, long permissionsVersion) {
        SchemaInfo view = plan.view();
        SqlCompiler compiler = new SqlCompiler(view, reflection.defaultSchema(), maxRows,
                reflection.engine().dialect(), reflection.engine().mutationCompiler(), maxQueryDepth, plan.access(),
                plan.functions(), capNestedLists);
        IntrospectionHandler handler = null;
        try {
            handler = new IntrospectionHandler(view, plan.access(), plan.functions(),
                    reflection.engine().mutationCompiler().supportsNestedInserts(),
                    reflection.engine().dialect().filterCapabilities(), reflection.engine().dialect().features());
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
     * token. The role is the one the request runs as. The engine the authentication filter resolved for
     * this request, if it is on the request's context, is the one served.
     */
    public EngineState resolveEngineState(Principal principal) {
        String projectId = TenantContext.getTenantId();
        Optional<ResolvedEngine> resolved = PropagatedContext.getOrEmpty().find(ResolvedEngine.class);
        if (resolved.isPresent() && resolved.get().serves(principal, projectId)) {
            return resolved.get().state();
        }
        String orgSlug = TenantContext.getOrgSlug();
        if (orgSlug == null && principal != null && principal.claims() != null) {
            orgSlug = principal.claims().orgSlug();
        }
        return resolveEngineState(orgSlug, projectId, principal);
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
        long seen = generation.get();
        EngineState state = engineFor(orgSlug, projectId, principal);
        if ((seen & 1) != 0 || generation.get() != seen) {
            // The default reflection was replaced while, or just before, this request was building on the old one.
            evict(projectId);
        }
        return state;
    }

    private EngineState engineFor(String orgSlug, String projectId, Principal principal) {
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
    private EngineState build(Reflection reflection, String projectId, String role, PermissionSet document) {
        PermissionDialect dialect = permissionDialect(reflection, projectId, role);
        PermissionSet permissions = document == null ? null : document.excluding(reservedSchemas::isReservedObject);
        AccessPlan plan;
        if (Principal.SERVICE.equals(role)) {
            plan = AccessPlan.allAccess(dialect, reflection.schema(),
                    permissions == null ? List.of() : permissions.functions());
        } else if (permissions == null) {
            plan = AccessPlan.forRole(dialect, reflection.schema(),
                    new RolePermissions(role, Map.of(), List.of(), Set.of()));
        } else {
            plan = AccessPlan.forRole(dialect, reflection.schema(), permissions.forRole(role));
        }
        EngineState state = assemble(reflection, plan, permissions == null ? NO_DOCUMENT : permissions.version());
        log.info("built_engine project={} role={} tables={} served_tables={} functions={}", projectId, role,
                reflection.schema().getTableNames().size(), plan.view().getTableNames().size(),
                plan.functions().all().size());
        return state;
    }

    /** The SQL this project's permissions compile to; a database the compiler does not speak serves no role. */
    private PermissionDialect permissionDialect(Reflection reflection, String projectId, String role) {
        try {
            return reflection.engine().dialect().permissionDialect();
        } catch (UnsupportedOperationException _) {
            log.warn("permissions_not_enforceable project={} role={} engine={}", projectId, role,
                    reflection.databaseType());
            throw new PermissionsUnavailableException("Permissions cannot be enforced on a "
                    + reflection.databaseType() + " project");
        }
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

    @Override
    public AccessPlan planFor(Principal principal, MutablePropagatedContext request) {
        if (documentMode()) {
            return null; // the document engine enforces permissions itself, on every request
        }
        EngineState state = resolveEngineState(principal);
        if (state != null && principal != null) {
            request.add(new ResolvedEngine(principal, TenantContext.getTenantId(), state));
        }
        return state == null ? null : state.plan();
    }

    @Override
    public Optional<RealtimeAccess> realtime(String orgSlug, String projectId, Principal principal,
                                             String subscriptionKey) {
        if (documentMode) {
            throw new SurfaceNotServedException("Realtime is not served for " + databaseType + " databases yet");
        }
        EngineState state = resolveEngineState(orgSlug, projectId, principal);
        if (state == null) {
            return Optional.empty();
        }
        SqlDialect dialect = state.compiler().dialect();
        if (!dialect.supports(DialectFeature.SUBSCRIPTIONS)) {
            throw new SubscriptionsUnsupportedException(dialect.databaseName() + " does not support subscriptions");
        }
        return tableFor(state, subscriptionKey).filter(table -> !reservedSchemas.isReservedObject(table)).flatMap(table ->
                state.plan().changes(table, sessionVariables(principal, projectId))
                        .map(filter -> new RealtimeAccess(table, filter, state.probes(), state.plan().view(),
                                state.permissionsVersion())));
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

    /** As {@link #evict}, for every project. */
    public void evictAll() {
        reflections.clear();
        tenantEngineStates.clear();
    }

    @Override
    public boolean isReserved(String schema) {
        return reservedSchemas.isReserved(schema);
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
        if (documentMode()) {
            throw new SurfaceNotServedException("This surface is not served for " + databaseType + " databases yet");
        }
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

    /**
     * Reflects the configured database again when its catalogs differ from what was last reflected. One
     * catalog query per call; the reload happens only on a change.
     *
     * @return whether the schema was reloaded
     * @throws DataAccessException the database could not be asked; the served schema is left as it is
     * @throws SchemaUnavailableException the change was seen but the database could not be reflected; the
     *         served schema is left as it is and the next call tries again
     */
    public boolean reloadIfSchemaChanged() {
        Reflection reflection = defaultReflection.get();
        if (reflection == null) {
            return false;
        }
        String current = fingerprint(reflection.engine());
        if (current == null || current.equals(reflectedFingerprint.get())) {
            return false;
        }
        log.info("schema_changed: reloading the schema of the configured database");
        reloadOrThrow();
        return true;
    }

    private String fingerprint(SqlEngine engine) {
        if (jdbcTemplate == null) {
            return null;
        }
        return engine.schemaLoader().fingerprint(bounded(FINGERPRINT_TIMEOUT_SECONDS));
    }

    private String fingerprintOrNull(SqlEngine engine) {
        try {
            return fingerprint(engine);
        } catch (DataAccessException e) {
            log.debug("schema_fingerprint_unavailable: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Reflects the configured database again. Called on DDL events from NatsCDCService. A database that
     * cannot be read leaves what is served as it is.
     */
    public void reload() {
        try {
            reloadOrThrow();
        } catch (SchemaUnavailableException e) {
            log.error("Schema reload failed — retaining previous state", e);
        }
    }

    private void reloadOrThrow() {
        SqlEngine engine = SqlEngineFactory.create(databaseType);
        String fingerprintBeforeLoad = fingerprintOrNull(engine);
        try {
            publish(loadDefault(engine), fingerprintBeforeLoad);
            log.info("Schema reloaded");
        } catch (Exception e) {
            // No fingerprint, so the next poll tries again.
            reflectedFingerprint.set(null);
            throw new SchemaUnavailableException("The database could not be reflected", e);
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
    List<String> discoverSchemas(JdbcTemplate jdbc) {
        return discoverSchemas(jdbc, databaseType);
    }

    private List<String> discoverSchemas(JdbcTemplate jdbc, String type) {
        List<String> fixed = SqlEngineFactory.fixedSchemas(type);
        if (!fixed.isEmpty()) {
            return fixed; // a file has no catalogue to list: its plugin names the schemas
        }
        String sql = MYSQL.equalsIgnoreCase(type)
                ? "SELECT schema_name FROM information_schema.schemata " +
                  "WHERE schema_name NOT IN ('information_schema', 'performance_schema', 'mysql', 'sys') " +
                  "ORDER BY schema_name"
                : "SELECT schema_name FROM information_schema.schemata " +
                  "WHERE schema_name NOT LIKE 'pg_%' " +
                  "AND schema_name != 'information_schema' " +
                  "ORDER BY schema_name";
        return reservedSchemas.filter(jdbc.queryForList(sql, String.class));
    }

    /**
     * A project is reflected with the engine its own vault record names; {@code app.database-type} is not
     * consulted. A database that cannot be read fails the request and nothing is cached: an empty schema
     * cached while a restore replaces the database would be served long after it is back (EXC-644).
     */
    Reflection reflectTenant(String orgSlug, String projectId) {
        TenantDatabase database = dataSourceManager.getDatabase(orgSlug, projectId);
        JdbcTemplate tenantJdbc = new JdbcTemplate(database.dataSource());
        String type = database.engine().type();

        SqlEngine engine = SqlEngineFactory.create(type);
        List<String> tenantSchemas;
        SchemaInfo schemaInfo = new SchemaInfo();
        try {
            tenantSchemas = discoverSchemas(tenantJdbc, type);
            loadMultiSchema(schemaInfo, tenantSchemas, engine.schemaLoader(), tenantJdbc);
        } catch (DataAccessException e) {
            log.warn("reflect_tenant_failed tenant={}/{} engine={}: {}", orgSlug, projectId, type, e.getMessage());
            throw new SchemaUnavailableException("The database of " + projectId + " could not be read", e);
        }

        log.info("reflected_tenant tenant={}/{} engine={} tables={}", orgSlug, projectId, type,
                schemaInfo.getTableNames().size());
        return new Reflection(schemaInfo, resolveDefaultSchema(tenantSchemas, schemaInfo), type, engine,
                SqlEngineFactory.createMutationExecutor(type), probesOn(tenantJdbc));
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
        new MultiSchemaLoader(reservedSchemas).load(schemaInfo, schemaList, loader, jdbc);
    }
}
