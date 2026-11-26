package io.github.excalibase.controller;

import graphql.parser.Parser;
import io.github.excalibase.compiler.ParsedQueries;
import io.github.excalibase.compiler.QueryRejectedException;
import io.github.excalibase.compiler.SqlCompiler;
import io.github.excalibase.config.GraphQLObservabilityInstrumentation;
import io.github.excalibase.schema.GraphqlSchemaManager;
import io.github.excalibase.schema.IntrospectionHandler;
import io.github.excalibase.security.Principal;
import io.github.excalibase.security.SecurityConstants;
import io.github.excalibase.service.QueryExecutionService;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** An introspection query is held to the depth and selection limits of any other query (EXC-831). */
class GraphqlControllerIntrospectionLimitsTest {

    @Test
    void anIntrospectionQueryOverTheLimits_isRefused_beforeTheSchemaIsWalked() {
        SqlCompiler compiler = mock(SqlCompiler.class);
        IntrospectionHandler introspection = mock(IntrospectionHandler.class);
        when(compiler.isIntrospection(any())).thenReturn(true);
        doThrow(new QueryRejectedException("Query selects more than 1000 fields once its fragments are expanded"))
                .when(compiler).enforceLimits(any());
        GraphqlSchemaManager schemaManager = mock(GraphqlSchemaManager.class);
        when(schemaManager.resolveEngineState(any())).thenReturn(new GraphqlSchemaManager.EngineState(
                compiler, introspection, null, null, "public", null, 1L));
        GraphQLObservabilityInstrumentation observability = mock(GraphQLObservabilityInstrumentation.class);
        when(observability.observe(any(), any(), any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(2)).get());
        GraphqlController controller = new GraphqlController(schemaManager, mock(QueryExecutionService.class),
                observability, null, new ParsedQueries(1_000_000, Parser::parse), null);
        MutableHttpRequest<Object> request = HttpRequest.POST("/p1/graphql", "");
        request.setAttribute(SecurityConstants.PRINCIPAL_ATTR, Principal.anonymous());

        HttpResponse<Object> answer = controller.graphql("p1", Map.of("query", "{ __schema { types { name } } }"), request);

        assertThat(answer.code()).isEqualTo(HttpStatus.BAD_REQUEST.getCode());
        verify(introspection, never()).execute(any(), any());
    }
}
