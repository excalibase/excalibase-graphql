package io.github.excalibase.controller;

import graphql.parser.Parser;
import io.github.excalibase.compiler.ParsedQueries;
import io.github.excalibase.compiler.UnknownFieldException;
import io.github.excalibase.config.GraphQLObservabilityInstrumentation;
import io.github.excalibase.schema.GraphqlSchemaManager;
import io.github.excalibase.security.Principal;
import io.github.excalibase.security.SecurityConstants;
import io.github.excalibase.service.QueryExecutionService;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A field the caller's schema lacks answers a stable code and names the role (EXC-804). */
class GraphqlControllerUnknownFieldTest {

    private final GraphqlSchemaManager schemaManager = mock(GraphqlSchemaManager.class);
    private GraphqlController controller;

    @BeforeEach
    void setUp() {
        GraphQLObservabilityInstrumentation observability = mock(GraphQLObservabilityInstrumentation.class);
        when(observability.observe(any(), any(), any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(2)).get());
        when(schemaManager.resolveEngineState(any())).thenThrow(new UnknownFieldException("publicTodos"));
        controller = new GraphqlController(schemaManager, mock(QueryExecutionService.class), observability, null,
                new ParsedQueries(1_000_000, Parser::parse), null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstError(Principal principal) {
        MutableHttpRequest<Object> request = HttpRequest.POST("/p1/graphql", "");
        if (principal != null) {
            request.setAttribute(SecurityConstants.PRINCIPAL_ATTR, principal);
        }
        HttpResponse<Object> answer = controller.graphql("p1", Map.of("query", "{ publicTodos { id } }"), request);
        return ((List<Map<String, Object>>) ((Map<String, Object>) answer.body()).get("errors")).getFirst();
    }

    @Test
    @SuppressWarnings("unchecked")
    void anAnonRequest_getsUnknownFieldNamingAnon() {
        Map<String, Object> error = firstError(Principal.anonymous());

        assertThat((Map<String, Object>) error.get("extensions")).containsEntry("code", "unknown_field");
        assertThat((String) error.get("message")).contains("publicTodos", "'anon'");
    }

    @Test
    void aRoleWithNoRule_isNamedInTheMessage() {
        Map<String, Object> error = firstError(new Principal("editor", false, null, Map.of()));

        assertThat((String) error.get("message")).contains("publicTodos", "'editor'");
    }

    @Test
    void aRequestWithNoPrincipal_keepsThePlainMessage() {
        Map<String, Object> error = firstError(null);

        assertThat(error).containsEntry("message", "Unknown field(s): publicTodos");
    }
}
