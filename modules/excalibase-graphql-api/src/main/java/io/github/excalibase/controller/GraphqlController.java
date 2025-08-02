package io.github.excalibase.controller;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import io.github.excalibase.config.GraphqlConfig;
import io.github.excalibase.service.DatabaseRoleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

/**
 * REST controller that provides GraphQL endpoint with PostgreSQL role-based security support.
 */
@RestController
@RequestMapping("/graphql")
public class GraphqlController {
    private final GraphqlConfig graphqlConfig;
    private final DatabaseRoleService databaseRoleService;

    public GraphqlController(GraphqlConfig graphqlConfig, DatabaseRoleService databaseRoleService) {
        this.graphqlConfig = graphqlConfig;
        this.databaseRoleService = databaseRoleService;
    }

    /**
     * Executes GraphQL queries and mutations with optional PostgreSQL role-based security.
     * 
     * @param request contains 'query' and optional 'variables'
     * @param databaseRole optional X-Database-Role header for PostgreSQL SET ROLE (RLS/CLS)
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

        try {
            GraphQL roleAwareGraphQL = graphqlConfig.getGraphQLForRole(databaseRole);
            databaseRoleService.setRole(databaseRole);

            // Execute GraphQL - all database operations automatically use the set role
            ExecutionResult result = roleAwareGraphQL.execute(ExecutionInput.newExecutionInput()
                .query(query)
                .variables(variables)
                .build());

        return ResponseEntity.ok(result.toSpecification());
            
        } finally {
            // Always reset role after request to prevent role leakage in connection pools
            databaseRoleService.resetRole();
        }
    }
}
