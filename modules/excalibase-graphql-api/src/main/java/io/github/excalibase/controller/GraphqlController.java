package io.github.excalibase.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import graphql.GraphQLException;
import io.github.excalibase.SqlDialect;
import io.github.excalibase.compiler.SqlCompilationException;
import io.github.excalibase.compiler.SqlCompiler;
import io.github.excalibase.errors.DataError;
import io.github.excalibase.errors.DataErrors;
import io.github.excalibase.config.GraphQLObservabilityInstrumentation;
import io.github.excalibase.permissions.PermissionEvaluationException;
import io.github.excalibase.permissions.PermissionsUnavailableException;
import io.github.excalibase.schema.GraphqlSchemaManager;
import io.github.excalibase.security.JwtAuthFilter;
import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.security.PermissionCheckFailedException;
import io.github.excalibase.security.PermissionErrors;
import io.github.excalibase.security.Principal;
import io.github.excalibase.security.SecurityConstants;
import io.github.excalibase.service.QueryExecutionService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.sql.SQLException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Single endpoint: parse GraphQL -> compile to SQL -> execute -> return JSON.
 * Delegates schema lifecycle to {@link GraphqlSchemaManager}
 * and query execution to {@link QueryExecutionService}.
 */
@RestController
public class GraphqlController {

    private static final Logger log = LoggerFactory.getLogger(GraphqlController.class);
    private static final String VARIABLES_KEY = "variables";
    private static final String ERRORS_KEY = "errors";
    private static final String MESSAGE_KEY = "message";
    private static final String INTERNAL_ERROR = "internal_error";

    private final GraphqlSchemaManager schemaManager;
    private final QueryExecutionService queryExecutor;
    private final GraphQLObservabilityInstrumentation observability;

    public GraphqlController(GraphqlSchemaManager schemaManager,
                             QueryExecutionService queryExecutor,
                             GraphQLObservabilityInstrumentation observability) {
        this.schemaManager = schemaManager;
        this.queryExecutor = queryExecutor;
        this.observability = observability;
    }

    /**
     * The GraphQL endpoint. The project lives in the URL path
     * ({@code /{projectId}/graphql}), mirroring auth ({@code /auth/{projectId}/…})
     * and functions ({@code /functions/v1/{projectId}/…}). {@link JwtAuthFilter}
     * has already read {@code projectId} from the path and applied the RLS
     * context, so this just executes. The {@code projectId} variable is bound
     * only to make the route match — there is no unscoped route, so RLS always
     * has a project to enforce against.
     */
    @PostMapping("/{projectId}/graphql")
    public ResponseEntity<Object> graphql(
            @PathVariable String projectId,
            @RequestBody Map<String, Object> request,
            HttpServletRequest httpRequest) {

        var jwtClaims = (JwtClaims) httpRequest.getAttribute(JwtAuthFilter.JWT_CLAIMS_ATTR);
        final Principal principal = (Principal) httpRequest.getAttribute(SecurityConstants.PRINCIPAL_ATTR);
        // anon has no user, even when its token (a publishable key) carries a subject.
        String userId = jwtClaims != null && principal != null && !Principal.ANON.equals(principal.role())
                ? jwtClaims.userId() : null;

        if (!(request.get("query") instanceof String query) || query.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    ERRORS_KEY, List.of(Map.of(MESSAGE_KEY, "Missing or invalid 'query' field"))));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> variables = request.containsKey(VARIABLES_KEY) && request.get(VARIABLES_KEY) instanceof Map
                ? (Map<String, Object>) request.get(VARIABLES_KEY) : Map.of();

        final String finalUserId = userId;
        final JwtClaims finalClaims = jwtClaims;
        final String finalQuery = query;

        return observability.observe(query, null, () -> {
            GraphqlSchemaManager.EngineState state = null;
            try {
                state = schemaManager.resolveEngineState(principal);
                if (state.compiler().isIntrospection(finalQuery)) {
                    return handleIntrospection(state, finalQuery, variables);
                }
                SqlCompiler.CompiledQuery compiled = state.compiler().compile(finalQuery, variables);
                return dispatchCompiled(compiled, state, finalUserId, finalClaims);
            } catch (Exception e) {
                return errorResponse(e, state == null ? null : state.compiler().dialect());
            }
        });
    }

    /**
     * Permission failures carry a stable code in {@code extensions.code}: a written row that fails its
     * check, or an expression that cannot apply to this request. Permissions that cannot be read refuse
     * the request with 503. A failure the client can act on (a violated constraint, a raised message, a
     * malformed value, a column it may not write) carries its code too; a request the compiler rejects
     * keeps its message; anything else is the server's fault, logged here and answered 500 without detail.
     */
    private static ResponseEntity<Object> errorResponse(Exception e, SqlDialect dialect) {
        if (e instanceof PermissionsUnavailableException) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(ERRORS_KEY, List.of(
                    PermissionErrors.graphqlError(PermissionsUnavailableException.CODE, "Permissions unavailable"))));
        }
        if (e instanceof PermissionEvaluationException evaluation) {
            return ResponseEntity.ok(Map.of(ERRORS_KEY,
                    List.of(PermissionErrors.graphqlError(evaluation.code(), evaluation.getMessage()))));
        }
        Optional<PermissionCheckFailedException> failed = PermissionCheckFailedException.find(e);
        if (failed.isPresent()) {
            log.info("GraphQL mutation refused: {}", failed.get().getMessage());
            return ResponseEntity.ok(Map.of(ERRORS_KEY,
                    List.of(PermissionErrors.graphqlError(failed.get().code(), failed.get().getMessage()))));
        }
        Optional<DataError> dataError = DataErrors.describe(e, dialect);
        if (dataError.isPresent()) {
            log.info("GraphQL request refused: {}", dataError.get().code());
            return ResponseEntity.ok(Map.of(ERRORS_KEY, List.of(dataError.get().graphqlError())));
        }
        if (isRequestError(e)) {
            return ResponseEntity.ok(Map.of(ERRORS_KEY, List.of(Map.of(MESSAGE_KEY,
                    e.getMessage() == null ? "Invalid request" : e.getMessage()))));
        }
        log.error("GraphQL request failed", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(ERRORS_KEY,
                List.of(PermissionErrors.graphqlError(INTERNAL_ERROR, "Internal server error"))));
    }

    /** A request the compiler or parser rejected: its message names the request's own mistake. */
    private static boolean isRequestError(Exception e) {
        return e instanceof IllegalArgumentException || e instanceof SqlCompilationException
                || e instanceof GraphQLException || e instanceof UnsupportedOperationException;
    }

    private ResponseEntity<Object> handleIntrospection(GraphqlSchemaManager.EngineState state,
                                                       String query, Map<String, Object> variables) {
        if (state.introspectionHandler() != null) {
            return ResponseEntity.ok(state.introspectionHandler().execute(query, variables));
        }
        return ResponseEntity.ok(Map.of("data", Map.of("__schema", Map.of("queryType", Map.of("name", "Query")))));
    }

    private ResponseEntity<Object> dispatchCompiled(SqlCompiler.CompiledQuery compiled,
                                                    GraphqlSchemaManager.EngineState state,
                                                    String userId, JwtClaims claims) throws SQLException, JsonProcessingException {
        if (compiled.isSequenced()) {
            return queryExecutor.executeSequence(compiled);
        }
        MapSqlParameterSource params = new MapSqlParameterSource(compiled.params());
        boolean isPostgres = "postgres".equalsIgnoreCase(schemaManager.getDatabaseType());

        // Postgres + JWT: run two-phase / plain through one transaction.
        boolean useContextPath = isPostgres
                && ((userId != null && !userId.isBlank()) || claims != null);
        if (useContextPath) {
            return queryExecutor.executeInContext(compiled, params, state.mutationExecutor());
        }

        // Legacy paths for non-Postgres or feature-disabled, no-JWT requests.
        if (compiled.isTwoPhase()) {
            return queryExecutor.executeTwoPhase(compiled, params, state.mutationExecutor());
        }
        return queryExecutor.executeQuery(compiled, params);
    }
}
