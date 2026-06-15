package io.github.excalibase.controller;

import io.github.excalibase.config.GraphQLObservabilityInstrumentation;
import io.github.excalibase.config.SecurityProperties;
import io.github.excalibase.schema.GraphqlSchemaManager;
import io.github.excalibase.service.QueryExecutionService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that raw database error detail is gated behind
 * {@code app.security.verbose-errors}: clients get a generic message by default
 * and the detailed message only when an operator opts in.
 */
class GraphqlControllerErrorTest {

    private static final String LEAKY_DETAIL =
            "ERROR: column \"secret_ssn\" does not exist; constraint users_pkey";

    private GraphqlController controllerWithVerbose(boolean verbose) {
        GraphqlSchemaManager schemaManager = mock(GraphqlSchemaManager.class);
        QueryExecutionService queryExecutor = mock(QueryExecutionService.class);
        GraphQLObservabilityInstrumentation observability = mock(GraphQLObservabilityInstrumentation.class);

        // The query path explodes with a leaky DB-style message.
        when(schemaManager.resolveEngineState(any()))
                .thenThrow(new RuntimeException(LEAKY_DETAIL));
        // observe() simply runs the supplied action.
        when(observability.observe(any(), any(), any())).thenAnswer(inv -> {
            Supplier<ResponseEntity<Object>> action = inv.getArgument(2);
            return action.get();
        });

        SecurityProperties props = new SecurityProperties(true, verbose, null, null, null);
        return new GraphqlController(schemaManager, queryExecutor, observability, props);
    }

    @SuppressWarnings("unchecked")
    private static String messageOf(ResponseEntity<Object> response) {
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        List<Map<String, Object>> errors = (List<Map<String, Object>>) body.get("errors");
        return (String) errors.get(0).get("message");
    }

    @Test
    @DisplayName("verbose-errors=false → client sees a generic message, not the DB detail")
    void verboseFalse_genericMessage() {
        GraphqlController controller = controllerWithVerbose(false);
        ResponseEntity<Object> response =
                controller.graphql(Map.of("query", "{ users { id } }"), mock(HttpServletRequest.class));

        String message = messageOf(response);
        assertThat(message).isEqualTo("query execution error");
        assertThat(message).doesNotContain("secret_ssn");
        assertThat(message).doesNotContain("ERROR:");
    }

    @Test
    @DisplayName("verbose-errors=true → client sees the detailed DB message")
    void verboseTrue_detailedMessage() {
        GraphqlController controller = controllerWithVerbose(true);
        ResponseEntity<Object> response =
                controller.graphql(Map.of("query", "{ users { id } }"), mock(HttpServletRequest.class));

        assertThat(messageOf(response)).contains("secret_ssn");
    }
}
