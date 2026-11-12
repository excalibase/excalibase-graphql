package io.github.excalibase.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.parser.Parser;
import io.github.excalibase.compiler.ParsedQueries;
import io.github.excalibase.config.GraphQLObservabilityInstrumentation;
import io.github.excalibase.config.datasource.TenantContext;
import io.github.excalibase.observability.requests.RequestMetrics;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.schema.StubbedSchemaManager;
import io.github.excalibase.security.JwtAuthFilter;
import io.github.excalibase.security.Principal;
import io.github.excalibase.security.SecurityConstants;
import io.github.excalibase.service.QueryExecutionService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParseOncePerRequestTest {

    private static final String ROLE = "app_authenticated";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AtomicInteger parses = new AtomicInteger();
    private GraphqlController controller;
    private Principal principal;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() throws Exception {
        SchemaInfo schema = new SchemaInfo();
        schema.setTableSchema("public.notes", "public");
        schema.addColumn("public.notes", "id", "integer");
        schema.addColumn("public.notes", "body", "text");
        schema.addPrimaryKey("public.notes", "id");
        String entry = "{\"table\":\"public.notes\",\"role\":\"" + ROLE + "\","
                + "\"select\":{\"filter\":{},\"columns\":\"*\"},"
                + "\"insert\":{\"check\":{},\"columns\":\"*\"}}";
        var schemaManager = StubbedSchemaManager.withDocument(schema,
                StubbedSchemaManager.document("p1", entry), (sql, params) -> false);

        QueryExecutionService executor = mock(QueryExecutionService.class);
        ResponseEntity<Object> answer = ResponseEntity.ok(Map.of("data", MAPPER.readTree("{\"notes\":[]}")));
        when(executor.executeQuery(any(), any())).thenReturn(answer);
        when(executor.executeInContext(any(), any(), any())).thenReturn(answer);
        when(executor.executeSequence(any())).thenReturn(answer);
        GraphQLObservabilityInstrumentation observability = mock(GraphQLObservabilityInstrumentation.class);
        when(observability.observe(any(), any(), any())).thenAnswer(call ->
                ((Supplier<?>) call.getArgument(2)).get());
        @SuppressWarnings("unchecked")
        ObjectProvider<RequestMetrics> metrics = mock(ObjectProvider.class);
        ParsedQueries parsedQueries = new ParsedQueries(1_000_000, text -> {
            parses.incrementAndGet();
            return Parser.parse(text);
        });
        controller = new GraphqlController(schemaManager, executor, observability, metrics, parsedQueries);

        principal = new Principal(ROLE, false, null, Map.of());
        request = mock(HttpServletRequest.class);
        when(request.getAttribute(SecurityConstants.PRINCIPAL_ATTR)).thenReturn(principal);
        when(request.getAttribute(JwtAuthFilter.JWT_CLAIMS_ATTR)).thenReturn(null);
        TenantContext.setTenantId("p1");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private ResponseEntity<Object> post(String query) {
        return controller.graphql("p1", Map.of("query", query), request);
    }

    @Test
    void dataQueryIsParsedOnce() {
        post("{ publicNotes { id body } }");

        assertThat(parses).hasValue(1);
    }

    @Test
    void mixedQueryIsParsedOnce() {
        post("{ __typename publicNotes { id } }");

        assertThat(parses).hasValue(1);
    }

    @Test
    void mutationIsParsedOnce() {
        post("mutation { createPublicNotes(input: {body: \"x\"}) { id } }");

        assertThat(parses).hasValue(1);
    }

    @Test
    void repeatedQueryHitsTheCache() {
        post("{ publicNotes { id } }");
        post("{ publicNotes { id } }");

        assertThat(parses).hasValue(1);
    }

    @Test
    void syntaxErrorAnswersAsBefore() {
        ResponseEntity<Object> first = post("{ publicNotes { id ");
        ResponseEntity<Object> second = post("{ publicNotes { id ");

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(String.valueOf(first.getBody())).contains("errors");
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(parses).hasValue(2);
    }
}
