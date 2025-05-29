package io.github.excalibase.controller;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/graphql")
public class GraphqlController {
    private final GraphQL graphQL;

    public GraphqlController(GraphQL graphQL) {
        this.graphQL = graphQL;
    }

    @PostMapping()
    public ResponseEntity<Map<String, Object>> graphql(@RequestBody Map<String, Object> request) {
        String query = (String) request.get("query");
        @SuppressWarnings("unchecked")
        Map<String, Object> variables = request.get("variables") != null
                ? (Map<String, Object>) request.get("variables")
                : Collections.emptyMap();

        ExecutionResult result = graphQL.execute(ExecutionInput.newExecutionInput()
                .query(query)
                .variables(variables)
                .build());

        return ResponseEntity.ok(result.toSpecification());
    }
}
