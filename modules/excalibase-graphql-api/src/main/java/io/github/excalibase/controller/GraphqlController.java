package io.github.excalibase.controller;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import io.github.excalibase.config.GraphqlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

/**
 * REST controller that provides GraphQL endpoint with security support.
 *
 * Supports two security models:
 *
 * 1. User Context RLS (Row-Level Security) - Supabase style:
 *    - UserContextFilter extracts X-User-Id header
 *    - Sets PostgreSQL session variable: SET LOCAL request.user_id = 'user-123'
 *    - RLS policies reference: USING (user_id = current_setting('request.user_id'))
 *    - Scales to millions of users without creating database roles
 *
 * 2. Role-Based CLS (Column-Level Security) - Legacy:
 *    - X-Database-Role header specifies PostgreSQL role
 *    - GraphQL schema filtered based on role privileges
 *    - Useful for role-based column visibility
 *
 * Example requests:
 *
 * RLS (User Context):
 * POST /graphql
 * X-User-Id: user-123
 * X-Claim-tenant_id: acme
 * { "query": "{ orders { id total } }" }
 *
 * CLS (Role-Based):
 * POST /graphql
 * X-Database-Role: hr_manager
 * { "query": "{ sensitive_customer { id salary } }" }
 *
 * Configuration:
 * app.security.user-context-enabled: true/false
 * app.security.role-based-schema: true/false
 */
@RestController
@RequestMapping("/graphql")
public class GraphqlController {
    private static final Logger log = LoggerFactory.getLogger(GraphqlController.class);
    private final GraphqlConfig graphqlConfig;

    public GraphqlController(GraphqlConfig graphqlConfig) {
        this.graphqlConfig = graphqlConfig;
    }

    /**
     * Executes GraphQL queries and mutations with optional role-based schema filtering.
     * User context for RLS is automatically set by UserContextFilter.
     *
     * @param request contains 'query' and optional 'variables'
     * @param databaseRole optional X-Database-Role header for CLS (schema filtering by role)
     * @return GraphQL execution result
     */
    @PostMapping()
    public ResponseEntity<Map<String, Object>> graphql(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "X-Database-Role", required = false) String databaseRole) {

        String query = (String) request.get("query");
        @SuppressWarnings("unchecked")
        Map<String, Object> variables = request.get("variables") != null
                ? (Map<String, Object>) request.get("variables")
                : Collections.emptyMap();

        // Get GraphQL instance with optional role-based schema filtering (CLS)
        // User context (RLS) is already set by UserContextFilter before this point
        GraphQL graphQL = graphqlConfig.getGraphQLForRole(databaseRole);

        // Execute GraphQL query
        ExecutionResult result = graphQL.execute(ExecutionInput.newExecutionInput()
                .query(query)
                .variables(variables)
                .build());
        log.info(query);
        return ResponseEntity.ok(result.toSpecification());
    }
}
