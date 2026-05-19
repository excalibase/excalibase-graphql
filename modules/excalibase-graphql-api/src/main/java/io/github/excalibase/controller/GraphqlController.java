package io.github.excalibase.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.excalibase.compiler.SqlCompiler;
import io.github.excalibase.config.GraphQLObservabilityInstrumentation;
import io.github.excalibase.schema.GraphqlSchemaManager;
import io.github.excalibase.security.JwtAuthFilter;
import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.security.RoleNotAllowedException;
import io.github.excalibase.service.QueryExecutionService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.sql.SQLException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Single endpoint: parse GraphQL -> compile to SQL -> execute -> return JSON.
 * Delegates schema lifecycle to {@link GraphqlSchemaManager}
 * and query execution to {@link QueryExecutionService}.
 */
@RestController
public class GraphqlController {

    private static final Logger log = LoggerFactory.getLogger(GraphqlController.class);
    private static final String VARIABLES_KEY = "variables";

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

    @PostMapping("/graphql")
    public ResponseEntity<Object> graphql(
            @RequestBody Map<String, Object> request,
            HttpServletRequest httpRequest) {

        var jwtClaims = (JwtClaims) httpRequest.getAttribute(JwtAuthFilter.JWT_CLAIMS_ATTR);
        String userId = jwtClaims != null ? jwtClaims.userId() : null;

        if (!(request.get("query") instanceof String query) || query.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "errors", List.of(Map.of("message", "Missing or invalid 'query' field"))));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> variables = request.containsKey(VARIABLES_KEY) && request.get(VARIABLES_KEY) instanceof Map
                ? (Map<String, Object>) request.get(VARIABLES_KEY) : Map.of();

        final String finalUserId = userId;
        final JwtClaims finalClaims = jwtClaims;
        final String finalQuery = query;

        return observability.observe(query, null, () -> {
            try {
                GraphqlSchemaManager.EngineState state = schemaManager.resolveEngineState(finalClaims);
                if (state.compiler().isIntrospection(finalQuery)) {
                    return handleIntrospection(state, finalQuery, variables);
                }
                SqlCompiler.CompiledQuery compiled = state.compiler().compile(finalQuery, variables);
                return dispatchCompiled(compiled, state, finalUserId, finalClaims);
            } catch (RoleNotAllowedException e) {
                // Let the @RestControllerAdvice translate this to HTTP 403 — do NOT
                // fall through to the catch-all that re-wraps as 200 with errors body.
                throw e;
            } catch (Exception e) {
                log.warn("GraphQL request failed", e);
                return ResponseEntity.ok(Map.of(
                        "errors", List.of(Map.of("message", extractErrorMessage(e)))));
            }
        });
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
        MapSqlParameterSource params = new MapSqlParameterSource(compiled.params());
        boolean isPostgres = "postgres".equalsIgnoreCase(schemaManager.getDatabaseType());

        // Postgres path: consolidate procedure / two-phase / plain through executeInContext
        // so they all share one transaction with RLS context + (optional) SET LOCAL ROLE.
        // Triggers when JWT is present OR role switching is configured (anon traffic).
        boolean useContextPath = isPostgres
                && ((userId != null && !userId.isBlank())
                    || claims != null
                    || queryExecutor.isRoleSwitchingEnabled());
        if (useContextPath) {
            return queryExecutor.executeInContext(compiled, params, state.mutationExecutor(), claims);
        }

        // Legacy paths for non-Postgres or feature-disabled, no-JWT requests.
        if (compiled.isProcedureCall() && compiled.procedureCallInfo() != null) {
            return queryExecutor.executeProcedureCall(compiled);
        }
        if (compiled.isTwoPhase()) {
            return queryExecutor.executeTwoPhase(compiled, params, state.mutationExecutor());
        }
        return queryExecutor.executeQuery(compiled, params);
    }

    private static String extractErrorMessage(Exception e) {
        String message = e.getMessage();
        if (message == null) return "Internal error";

        int sqlError = message.indexOf("ERROR:");
        if (sqlError >= 0) return message.substring(sqlError);

        if (message.contains("StatementCallback") || message.contains("PreparedStatementCallback")) {
            int bracket = message.indexOf("; ");
            if (bracket > 0) return message.substring(bracket + 2);
        }
        return message;
    }
}
