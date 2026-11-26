package io.github.excalibase.controller;

import graphql.parser.Parser;
import io.github.excalibase.compiler.ParsedQueries;
import io.github.excalibase.config.GraphQLObservabilityInstrumentation;
import io.github.excalibase.document.CountingProvider;
import io.github.excalibase.document.DocumentEngines;
import io.github.excalibase.permissions.BoolExp;
import io.github.excalibase.permissions.ColumnSet;
import io.github.excalibase.permissions.PermissionProvider;
import io.github.excalibase.permissions.PermissionSet;
import io.github.excalibase.permissions.PermissionsUnavailableException;
import io.github.excalibase.permissions.SelectPermission;
import io.github.excalibase.permissions.TablePermissions;
import io.github.excalibase.schema.GraphqlSchemaManager;
import io.github.excalibase.security.Principal;
import io.github.excalibase.security.SecurityConstants;
import io.github.excalibase.service.QueryExecutionService;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** A document database's requests go to its engine, for every role, and never to the SQL path. */
class GraphqlControllerDocumentTest {

    private static final String PROJECT = "p1";

    private final GraphqlSchemaManager schemaManager = mock(GraphqlSchemaManager.class);
    private final QueryExecutionService executor = mock(QueryExecutionService.class);
    private PermissionSet served;
    private DocumentEngines engines;
    private GraphqlController controller;

    @BeforeEach
    void setUp() throws IOException {
        Path collections = Files.createTempFile("collections", ".yaml");
        Files.writeString(collections, "collections:\n  - name: orders\n    fields:\n      owner: string\n");
        served = new PermissionSet(PROJECT, 1, List.of(new TablePermissions("app.orders", "user",
                Optional.of(new SelectPermission(BoolExp.TRUE, ColumnSet.ALL, null, false)),
                Optional.empty(), Optional.empty(), Optional.empty())), List.of(), List.of());
        PermissionProvider permissions = new PermissionProvider() {
            @Override public PermissionSet permissionsFor(String projectId) {
                if (served == null) {
                    throw new PermissionsUnavailableException("nothing loaded");
                }
                return served;
            }
            @Override public void evict(String projectId) { }
        };
        engines = new DocumentEngines(CountingProvider.TYPE, "", "app", collections.toString(), 1000, 30, PROJECT, permissions);
        GraphQLObservabilityInstrumentation observability = mock(GraphQLObservabilityInstrumentation.class);
        when(observability.observe(any(), any(), any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(2)).get());
        controller = new GraphqlController(schemaManager, executor, observability, null,
                new ParsedQueries(1_000_000, text -> Parser.parse(text)), engines);
    }

    @AfterEach
    void tearDown() {
        engines.close();
    }

    private static Principal user() {
        return new Principal("user", false, null, Map.of());
    }

    private HttpResponse<Object> post(Principal principal) {
        MutableHttpRequest<Object> request = HttpRequest.POST("/p1/graphql", "");
        request.setAttribute(SecurityConstants.PRINCIPAL_ATTR, principal);
        return controller.graphql(PROJECT, Map.of("query", "{ orders { id } }"), request);
    }

    @Test
    void aRoleIsAnsweredByTheDocumentEngineNeverBySql() {
        HttpResponse<Object> answer = post(user());

        assertThat(answer.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(answer.body()).isEqualTo(Map.of("data", Map.of()));
        verifyNoInteractions(schemaManager, executor);
    }

    @Test
    void theServiceRoleIsRefusedWithoutReachingTheEngine() {
        HttpResponse<Object> answer = post(new Principal(Principal.SERVICE, true, null, Map.of()));

        assertThat(answer.code()).isEqualTo(HttpStatus.FORBIDDEN.getCode());
        assertThat(String.valueOf(answer.body())).contains("service_role_unsupported");
        verifyNoInteractions(schemaManager, executor);
    }

    @Test
    void aPathProjectThatIsNotThisDeploymentsIsAnswered404() {
        MutableHttpRequest<Object> request = HttpRequest.POST("/other/graphql", "");
        request.setAttribute(SecurityConstants.PRINCIPAL_ATTR, user());

        HttpResponse<Object> answer = controller.graphql("other", Map.of("query", "{ orders { id } }"), request);

        assertThat(answer.code()).isEqualTo(HttpStatus.NOT_FOUND.getCode());
        verifyNoInteractions(schemaManager, executor);
    }

    @Test
    void permissionsThatCannotBeReadAreRefusedAs503() {
        served = null;

        HttpResponse<Object> answer = post(user());

        assertThat(answer.code()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.getCode());
        assertThat(String.valueOf(answer.body())).contains("permissions_unavailable");
    }
}
