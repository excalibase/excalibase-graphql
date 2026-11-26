package io.github.excalibase.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import graphql.GraphQLException;
import graphql.language.Document;
import graphql.language.OperationDefinition;
import io.github.excalibase.SqlDialect;
import io.github.excalibase.compiler.ParsedQueries;
import io.github.excalibase.compiler.SqlCompilationException;
import io.github.excalibase.compiler.QueryRejectedException;
import io.github.excalibase.compiler.SqlCompiler;
import io.github.excalibase.errors.DataError;
import io.github.excalibase.errors.DataErrors;
import io.github.excalibase.observability.requests.RequestMetrics;
import io.github.excalibase.config.GraphQLObservabilityInstrumentation;
import io.github.excalibase.document.DocumentEngines;
import io.github.excalibase.permissions.PermissionEvaluationException;
import io.github.excalibase.permissions.PermissionsUnavailableException;
import io.github.excalibase.schema.GraphqlSchemaManager;
import io.github.excalibase.security.JwtAuthFilter;
import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.security.PermissionCheckFailedException;
import io.github.excalibase.security.PermissionErrors;
import io.github.excalibase.security.Principal;
import io.github.excalibase.security.SecurityConstants;
import io.github.excalibase.security.UnknownProjectException;
import io.github.excalibase.service.QueryExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.excalibase.http.Reply;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.inject.Inject;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.sql.SQLException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Single endpoint: parse GraphQL -> compile to SQL -> execute -> return JSON.
 * Delegates schema lifecycle to {@link GraphqlSchemaManager}
 * and query execution to {@link QueryExecutionService}.
 */
@Controller
@Produces({MediaType.APPLICATION_JSON, MediaType.ALL})
@ExecuteOn(TaskExecutors.BLOCKING)
public class GraphqlController {

    private static final Logger log = LoggerFactory.getLogger(GraphqlController.class);
    private static final String VARIABLES_KEY = "variables";
    private static final String ERRORS_KEY = "errors";
    private static final String DATA_KEY = "data";
    private static final String MESSAGE_KEY = "message";
    private static final String INTERNAL_ERROR = "internal_error";

    private final GraphqlSchemaManager schemaManager;
    private final QueryExecutionService queryExecutor;
    private final GraphQLObservabilityInstrumentation observability;
    private final boolean requestMetricsOn;
    private final ParsedQueries parsedQueries;
    private final DocumentEngines documentEngines;

    public GraphqlController(GraphqlSchemaManager schemaManager,
                             QueryExecutionService queryExecutor,
                             GraphQLObservabilityInstrumentation observability,
                             @Nullable RequestMetrics requestMetrics,
                             ParsedQueries parsedQueries) {
        this(schemaManager, queryExecutor, observability, requestMetrics, parsedQueries, null);
    }

    @Inject
    public GraphqlController(GraphqlSchemaManager schemaManager,
                             QueryExecutionService queryExecutor,
                             GraphQLObservabilityInstrumentation observability,
                             @Nullable RequestMetrics requestMetrics,
                             ParsedQueries parsedQueries,
                             @Nullable DocumentEngines documentEngines) {
        this.documentEngines = documentEngines;
        this.schemaManager = schemaManager;
        this.queryExecutor = queryExecutor;
        this.observability = observability;
        this.requestMetricsOn = requestMetrics != null;
        this.parsedQueries = parsedQueries;
    }

    /**
     * The GraphQL endpoint. The project lives in the URL path
     * ({@code /{projectId}/graphql}), mirroring auth ({@code /auth/{projectId}/…})
     * and functions ({@code /functions/v1/{projectId}/…}). {@link JwtAuthFilter}
     * has already read {@code projectId} from the path and applied the RLS
     * context, so this just executes. The {@code projectId} variable is bound
     * only to make the route match — there is no unscoped route, so RLS always
     * has a project to enforce against.
     */
    @Post("/{projectId}/graphql")
    public HttpResponse<Object> graphql(
            @PathVariable String projectId,
            @Body Map<String, Object> request,
            HttpRequest<?> httpRequest) {

        var jwtClaims = (JwtClaims) httpRequest.getAttribute(JwtAuthFilter.JWT_CLAIMS_ATTR).orElse(null);
        final Principal principal = (Principal) httpRequest.getAttribute(SecurityConstants.PRINCIPAL_ATTR).orElse(null);
        // anon has no user, even when its token (a publishable key) carries a subject.
        String userId = jwtClaims != null && principal != null && !Principal.ANON.equals(principal.role())
                ? jwtClaims.userId() : null;

        if (!(request.get("query") instanceof String query) || query.isBlank()) {
            return Reply.badRequest().body(Map.of(
                    ERRORS_KEY, List.of(Map.of(MESSAGE_KEY, "Missing or invalid 'query' field"))));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> variables = request.containsKey(VARIABLES_KEY) && request.get(VARIABLES_KEY) instanceof Map
                ? (Map<String, Object>) request.get(VARIABLES_KEY) : Map.of();

        if (documentEngines != null) {
            return observability.observe(query, null, () -> documentAnswer(documentEngines, projectId, principal, query, variables));
        }

        final String finalUserId = userId;
        final JwtClaims finalClaims = jwtClaims;
        final String finalQuery = query;

        HttpResponse<Object> answer = observability.observe(query, null, () -> {
            GraphqlSchemaManager.EngineState state = null;
            try {
                state = schemaManager.resolveEngineState(principal);
                Document document = parsedQueries.parse(finalQuery);
                if (state.compiler().isIntrospection(document)) {
                    HttpResponse<Object> introspection = handleIntrospection(state, finalQuery, variables);
                    Optional<Document> dataPart = MixedQuery.dataPart(document);
                    if (dataPart.isEmpty()) {
                        return introspection;
                    }
                    SqlCompiler.CompiledQuery compiled = state.compiler().compile(dataPart.get(), variables);
                    return merge(introspection, SelectionOrder.apply(
                            dispatchCompiled(compiled, state, finalUserId, finalClaims, isMutation(dataPart.get())), dataPart.get()));
                }
                SqlCompiler.CompiledQuery compiled = state.compiler().compile(document, variables);
                return SelectionOrder.apply(dispatchCompiled(compiled, state, finalUserId, finalClaims, isMutation(document)), document);
            } catch (Exception e) {
                return errorResponse(e, state == null ? null : state.compiler().dialect());
            } catch (StackOverflowError e) {
                log.warn("GraphQL request refused: nesting too deep for the compiler");
                return Reply.badRequest().body(Map.of(
                        ERRORS_KEY, List.of(Map.of(MESSAGE_KEY, "Query is nested too deeply"))));
            }
        });
        if (requestMetricsOn && answer.body() instanceof Map<?, ?> body && body.containsKey(ERRORS_KEY)) {
            httpRequest.getAttributes().put(RequestMetrics.GRAPHQL_ERRORS_ATTR, Boolean.TRUE);
        }
        return answer;
    }

    /** A document database's answer: its engine enforces the permission document itself, for every role. */
    private HttpResponse<Object> documentAnswer(DocumentEngines engines, String projectId, Principal principal,
                                                String query, Map<String, Object> variables) {
        Principal caller = principal == null ? Principal.anonymous() : principal;
        if (caller.bypass()) {
            return Reply.status(HttpStatus.FORBIDDEN).body(Map.of(ERRORS_KEY, List.of(PermissionErrors.graphqlError(
                    "service_role_unsupported", "The service role is not served on this database yet"))));
        }
        try {
            return Reply.ok(engines.execute(projectId, query, variables, caller));
        } catch (UnknownProjectException e) {
            return Reply.status(HttpStatus.NOT_FOUND).body(Map.of(ERRORS_KEY, List.of(
                    PermissionErrors.graphqlError("project_not_found", "Project not found"))));
        } catch (Exception e) {
            return errorResponse(e, null);
        }
    }

    /**
     * Permission failures carry a stable code in {@code extensions.code}: a written row that fails its
     * check, or an expression that cannot apply to this request. Permissions that cannot be read refuse
     * the request with 503. A failure the client can act on (a violated constraint, a raised message, a
     * malformed value, a column it may not write) carries its code too; a request the compiler rejects
     * keeps its message; anything else is the server's fault, logged here and answered 500 without detail.
     */
    private static HttpResponse<Object> errorResponse(Exception e, SqlDialect dialect) {
        if (e instanceof PermissionsUnavailableException) {
            return Reply.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(ERRORS_KEY, List.of(
                    PermissionErrors.graphqlError(PermissionsUnavailableException.CODE, "Permissions unavailable"))));
        }
        if (e instanceof QueryRejectedException) {
            log.info("GraphQL request refused: {}", e.getMessage());
            return Reply.badRequest().body(Map.of(ERRORS_KEY, List.of(Map.of(MESSAGE_KEY, e.getMessage()))));
        }
        if (e instanceof PermissionEvaluationException evaluation) {
            return Reply.ok(Map.of(ERRORS_KEY,
                    List.of(PermissionErrors.graphqlError(evaluation.code(), evaluation.getMessage()))));
        }
        Optional<PermissionCheckFailedException> failed = PermissionCheckFailedException.find(e);
        if (failed.isPresent()) {
            log.info("GraphQL mutation refused: {}", failed.get().getMessage());
            return Reply.ok(Map.of(ERRORS_KEY,
                    List.of(PermissionErrors.graphqlError(failed.get().code(), failed.get().getMessage()))));
        }
        Optional<DataError> dataError = DataErrors.describe(e, dialect);
        if (dataError.isPresent()) {
            log.info("GraphQL request refused: {}", dataError.get().code());
            Map<String, Object> body = Map.of(ERRORS_KEY, List.of(dataError.get().graphqlError()));
            return dataError.get().status() >= HttpStatus.INTERNAL_SERVER_ERROR.getCode()
                    ? Reply.status(HttpStatus.valueOf(dataError.get().status())).body(body)
                    : Reply.ok(body);
        }
        if (isRequestError(e)) {
            return Reply.ok(Map.of(ERRORS_KEY, List.of(Map.of(MESSAGE_KEY,
                    e.getMessage() == null ? "Invalid request" : e.getMessage()))));
        }
        log.error("GraphQL request failed", e);
        return Reply.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(ERRORS_KEY,
                List.of(PermissionErrors.graphqlError(INTERNAL_ERROR, "Internal server error"))));
    }

    /** A request the compiler or parser rejected: its message names the request's own mistake. */
    private static boolean isRequestError(Exception e) {
        return e instanceof IllegalArgumentException || e instanceof SqlCompilationException
                || e instanceof GraphQLException || e instanceof UnsupportedOperationException;
    }

    private HttpResponse<Object> handleIntrospection(GraphqlSchemaManager.EngineState state,
                                                       String query, Map<String, Object> variables) {
        if (state.introspectionHandler() != null) {
            return Reply.ok(state.introspectionHandler().execute(query, variables));
        }
        return Reply.ok(Map.of(DATA_KEY, Map.of("__schema", Map.of("queryType", Map.of("name", "Query")))));
    }

    /**
     * One answer for a query that selects introspection and data: the introspection result in the query's
     * field order, each data field replaced by what the database answered. A data error answers alone.
     */
    @SuppressWarnings("unchecked")
    private static HttpResponse<Object> merge(HttpResponse<Object> introspection, HttpResponse<Object> data) {
        if (!(data.body() instanceof Map<?, ?> dataBody) || dataBody.containsKey(ERRORS_KEY)
                || !(introspection.body() instanceof Map<?, ?> introspectionBody)) {
            return data;
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        if (introspectionBody.get(DATA_KEY) instanceof Map<?, ?> introspected) {
            merged.putAll((Map<String, Object>) introspected);
        }
        if (dataBody.get(DATA_KEY) instanceof JsonNode answered) {
            answered.properties().forEach(field -> merged.put(field.getKey(), field.getValue()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(DATA_KEY, merged);
        if (introspectionBody.containsKey(ERRORS_KEY)) {
            body.put(ERRORS_KEY, introspectionBody.get(ERRORS_KEY));
        }
        return Reply.ok(body);
    }

    private static boolean isMutation(Document document) {
        return document.getDefinitionsOfType(OperationDefinition.class).stream()
                .anyMatch(operation -> operation.getOperation() == OperationDefinition.Operation.MUTATION);
    }

    private HttpResponse<Object> dispatchCompiled(SqlCompiler.CompiledQuery compiled,
                                                    GraphqlSchemaManager.EngineState state,
                                                    String userId, JwtClaims claims, boolean mutation)
            throws SQLException, JsonProcessingException {
        if (compiled.isSequenced()) {
            return queryExecutor.executeSequence(compiled);
        }
        MapSqlParameterSource params = new MapSqlParameterSource(compiled.params());
        // A write and its read-back share one connection and transaction of the project's own database.
        boolean hasCaller = (userId != null && !userId.isBlank()) || claims != null;
        // A write always runs in a transaction it commits itself, so a connection lost at commit is told apart from one lost before it.
        if (compiled.isTwoPhase() || hasCaller || mutation) {
            return queryExecutor.executeInContext(compiled, params, state.mutationExecutor());
        }
        return queryExecutor.executeQuery(compiled, params);
    }
}
