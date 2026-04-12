package io.github.excalibase.controller;

import io.github.excalibase.compiler.SqlCompiler;
import io.github.excalibase.config.GraphQLObservabilityInstrumentation;
import io.github.excalibase.schema.GraphqlSchemaManager;
import io.github.excalibase.security.JwtAuthFilter;
import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.service.QueryExecutionService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
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
        Map<String, Object> variables = request.containsKey("variables") && request.get("variables") instanceof Map
                ? (Map<String, Object>) request.get("variables") : Map.of();

        final String finalUserId = userId;
        final JwtClaims finalClaims = jwtClaims;
        final String finalQuery = query;

        return observability.observe(query, null, () -> {
            try {
                GraphqlSchemaManager.EngineState state = schemaManager.resolveEngineState(finalClaims);

                // Introspection passthrough to GraphQL-Java
                if (state.compiler().isIntrospection(finalQuery)) {
                    if (state.introspectionHandler() != null) {
                        return ResponseEntity.ok(state.introspectionHandler().execute(finalQuery, variables));
                    }
                    return ResponseEntity.ok(Map.of("data", Map.of("__schema", Map.of("queryType", Map.of("name", "Query")))));
                }

                SqlCompiler.CompiledQuery compiled = state.compiler().compile(finalQuery, variables);
                MapSqlParameterSource params = new MapSqlParameterSource(compiled.params());

                if (compiled.isProcedureCall() && compiled.procedureCallInfo() != null) {
                    return queryExecutor.executeProcedureCall(compiled);
                }

                if (compiled.isTwoPhase()) {
                    return queryExecutor.executeTwoPhase(compiled, params, state.mutationExecutor());
                }

                if (finalUserId != null && !finalUserId.isBlank()
                        && "postgres".equalsIgnoreCase(schemaManager.getDatabaseType())) {
                    return queryExecutor.executeWithRlsContext(compiled, finalUserId, finalClaims);
                }

                return queryExecutor.executeQuery(compiled, params);

            } catch (Exception e) {
                log.warn("GraphQL request failed", e);
                return ResponseEntity.ok(Map.of(
                        "errors", List.of(Map.of("message", extractErrorMessage(e)))));
            }
        });
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
