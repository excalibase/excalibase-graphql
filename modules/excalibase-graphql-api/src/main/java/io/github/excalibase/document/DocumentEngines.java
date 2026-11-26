package io.github.excalibase.document;

import io.github.excalibase.metadata.DocumentCollections;
import io.github.excalibase.permissions.PermissionProvider;
import io.github.excalibase.permissions.PermissionSet;
import io.github.excalibase.permissions.PermissionsUnavailableException;
import io.github.excalibase.security.Principal;
import io.github.excalibase.security.UnknownProjectException;
import io.github.excalibase.spi.QueryEngine;
import io.github.excalibase.spi.QueryEngineFactory;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The engine of a document database (MongoDB, DocumentDB), built from the permission document the project is served
 * under. A new document version builds a new engine and closes the old one, so a rule change takes effect on the
 * next request and a rule that cannot be enforced is refused, never skipped. There is no service-role bypass here:
 * the controller refuses the service role (403) before a request reaches the engine, and every other role is judged by
 * the document. The engine serves its own project only; a request for another one is refused.
 */
@Context
@Requires(property = "app.database-type", pattern = "(?i)mongodb|documentdb")
public class DocumentEngines implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DocumentEngines.class);

    private record Built(long version, QueryEngine engine) {
    }

    private final String databaseType;
    private final Map<String, Object> connection;
    private final PermissionProvider permissions;
    private final String projectId;
    private final AtomicReference<Built> current = new AtomicReference<>();

    @Inject
    public DocumentEngines(@Value("${app.database-type}") String databaseType,
                           @Value("${app.mongodb.uri:}") String uri,
                           @Value("${app.mongodb.database:}") String database,
                           @Value("${app.mongodb.collections-file:}") String collectionsFile,
                           @Value("${app.mongodb.max-time-ms:5000}") long maxTimeMs,
                           @Value("${app.max-rows:30}") int maxRows,
                           @Value("${app.project-id:}") String projectId,
                           @Nullable PermissionProvider permissions) {
        if (permissions == null || !permissions.configured()) {
            throw new IllegalStateException("A " + databaseType + " database is served only under a permission document: "
                    + "set app.security.jwt-enabled and a metadata source (METADATA_SOURCE), there is no open mode");
        }
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalStateException("A " + databaseType + " database needs app.project-id (APP_PROJECT_ID)");
        }
        this.databaseType = databaseType;
        this.permissions = permissions;
        this.projectId = projectId;
        Map<String, Object> settings = new HashMap<>();
        settings.put("uri", uri);
        settings.put("database", database);
        settings.put("collections", DocumentCollections.read(collectionsFile));
        settings.put("maxTimeMs", maxTimeMs);
        settings.put("maxRows", (long) maxRows);
        this.connection = Map.copyOf(settings);
    }

    /** Builds the engine for the document now served, so an invalid document stops the start rather than the first request. */
    @PostConstruct
    void start() {
        try {
            if (!engineFor(projectId).healthy()) {
                log.warn("document_database_unreachable: the database does not answer a ping; /health reports it down");
            }
        } catch (PermissionsUnavailableException _) {
            log.warn("document_permissions_not_loaded_yet: the first request builds the engine");
        }
    }

    /**
     * Answers one GraphQL request as {@code principal} on this deployment's own project.
     *
     * @throws UnknownProjectException {@code project} is not the project this deployment serves
     * @throws PermissionsUnavailableException the document cannot be read, or holds a rule that cannot be enforced
     */
    public Map<String, Object> execute(String project, String query, Map<String, Object> variables, Principal principal) {
        if (!projectId.equals(project)) {
            throw new UnknownProjectException(String.valueOf(project));
        }
        QueryEngine engine;
        try {
            engine = engineFor(projectId);
        } catch (IllegalStateException refused) {
            log.error("document_permissions_refused: {}", refused.getMessage());
            throw new PermissionsUnavailableException("The permission document cannot be enforced", refused);
        }
        return engine.execute(query, variables, new RequestCaller(principal, projectId));
    }

    /** Whether the database answers now; false when no engine could be built yet (permissions not loaded, a refused rule). */
    public boolean healthy() {
        try {
            return engineFor(projectId).healthy();
        } catch (RuntimeException e) {
            log.warn("document_health: no engine to ask ({})", e.getClass().getSimpleName());
            return false;
        }
    }

    private QueryEngine engineFor(String project) {
        PermissionSet document = permissions.permissionsFor(project);
        Built held = current.get();
        if (held != null && held.version() == document.version()) {
            return held.engine();
        }
        synchronized (this) {
            held = current.get();
            if (held != null && held.version() == document.version()) {
                return held.engine();
            }
            Map<String, Object> settings = new HashMap<>(connection);
            settings.put("permissions", document);
            QueryEngine built = QueryEngineFactory.create(databaseType, settings);
            current.set(new Built(document.version(), built));
            if (held != null) {
                held.engine().close();
            }
            log.info("document_engine_built type={} permissions_version={}", databaseType, document.version());
            return built;
        }
    }

    @PreDestroy
    @Override
    public void close() {
        Built held = current.getAndSet(null);
        if (held != null) {
            held.engine().close();
        }
    }
}
