package io.github.excalibase.mongodb;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphqlErrorBuilder;
import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.execution.DataFetcherExceptionHandler;
import graphql.execution.DataFetcherExceptionHandlerParameters;
import graphql.execution.DataFetcherExceptionHandlerResult;
import graphql.execution.instrumentation.ChainedInstrumentation;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingFieldSelectionSet;
import graphql.schema.SelectedField;
import io.github.excalibase.rls.Operation;
import io.github.excalibase.rls.UserContext;
import io.github.excalibase.spi.QueryEngine;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;

/**
 * GraphQL over declared MongoDB collections. A request becomes one aggregation pipeline per root field;
 * the caller's row filter is its first stage and their column rules shape both the schema and the projection.
 */
// the BSON driver reads and writes dates as java.util.Date; there is no other type to exchange them in
@SuppressWarnings("java:S2143")
final class MongoQueryEngine implements QueryEngine {

    private static final Logger log = LoggerFactory.getLogger(MongoQueryEngine.class);
    private static final String CALLER = "caller";
    private static final String VIEW = "view";
    private static final int MAX_DEPTH = 3;
    private static final int MAX_CACHED_SCHEMAS = 64;

    private final Catalog catalog;
    private final RowSecurity rows;
    private final ColumnSecurity columns;
    private final PipelineRunner runner;
    private final WhereCompiler where;
    private final GraphqlSchemas schemas;
    private final int maxRows;
    private final int maxFields;
    private final Map<String, GraphQL> engines = new ConcurrentHashMap<>();

    MongoQueryEngine(Catalog catalog, RowSecurity rows, ColumnSecurity columns, PipelineRunner runner,
                     MongoCapabilities capabilities, int maxRows, int maxFields) {
        this.catalog = catalog;
        this.rows = rows;
        this.columns = columns;
        this.runner = runner;
        this.where = new WhereCompiler(capabilities);
        this.schemas = new GraphqlSchemas(catalog, capabilities, maxRows);
        this.maxRows = maxRows;
        this.maxFields = maxFields;
    }

    @Override
    public Map<String, Object> execute(String query, Map<String, Object> variables, UserContext caller) {
        MaskView view = MaskView.of(catalog, columns, caller);
        ExecutionInput input = ExecutionInput.newExecutionInput()
                .query(query)
                .variables(variables == null ? Map.of() : variables)
                .graphQLContext(Map.of(CALLER, caller, VIEW, view))
                .build();
        ExecutionResult result = engineFor(view).execute(input);
        return result.toSpecification();
    }

    @Override
    public void close() {
        runner.close();
    }

    private GraphQL engineFor(MaskView view) {
        if (engines.size() >= MAX_CACHED_SCHEMAS) {
            engines.clear();
        }
        return engines.computeIfAbsent(view.key(), key -> GraphQL
                .newGraphQL(schemas.build(view, this::list, this::byId))
                .defaultDataFetcherExceptionHandler(new SafeErrors())
                .instrumentation(new ChainedInstrumentation(List.of(
                        new MaxQueryDepthInstrumentation(MAX_DEPTH),
                        new MaxQueryComplexityInstrumentation(maxFields))))
                .build());
    }

    private Object list(DataFetchingEnvironment env) {
        CollectionDef def = collection(env);
        UserContext caller = env.getGraphQlContext().get(CALLER);
        MaskView view = env.getGraphQlContext().get(VIEW);
        Cond user = where.compile(def, view.filterable(def), whereArgument(env));
        ListQuery query = new ListQuery(user, order(orderBy(env)), number(env, "limit", maxRows), number(env, "offset", 0));
        List<Document> stages = PipelineCompiler.list(def, rows.filter(def.name(), caller, Operation.SELECT), query,
                projection(def, view, env.getSelectionSet()), maxRows);
        return runner.run(def.name(), stages).stream().map(doc -> row(def, doc, env.getSelectionSet())).toList();
    }

    private Object byId(DataFetchingEnvironment env) {
        CollectionDef def = collection(env);
        UserContext caller = env.getGraphQlContext().get(CALLER);
        MaskView view = env.getGraphQlContext().get(VIEW);
        List<Document> stages = PipelineCompiler.byId(def, rows.filter(def.name(), caller, Operation.SELECT),
                env.getArgument("id"), projection(def, view, env.getSelectionSet()));
        List<Document> found = runner.run(def.name(), stages);
        return found.isEmpty() ? null : row(def, found.getFirst(), env.getSelectionSet());
    }

    /** An argument sent as an explicit null is the same as one left out. */
    private static int number(DataFetchingEnvironment env, String name, int fallback) {
        Integer value = env.getArgument(name);
        return value == null ? fallback : value;
    }

    private static Map<String, Object> whereArgument(DataFetchingEnvironment env) {
        Map<String, Object> filter = env.getArgument("where");
        return filter == null ? Map.of() : filter;
    }

    private CollectionDef collection(DataFetchingEnvironment env) {
        String field = env.getField().getName();
        return catalog.all().stream().filter(def -> def.name().equals(field) || GraphqlSchemas.byIdField(def).equals(field))
                .findFirst().orElseThrow(() -> new IllegalStateException("No collection behind " + field));
    }

    private static List<Map<String, Object>> orderBy(DataFetchingEnvironment env) {
        List<Map<String, Object>> terms = env.getArgument("orderBy");
        return terms == null ? List.of() : terms;
    }

    private static List<SortKey> order(List<Map<String, Object>> orderBy) {
        List<SortKey> keys = new ArrayList<>();
        for (Map<String, Object> term : orderBy) {
            keys.add(new SortKey(String.valueOf(term.get("field")), !"DESC".equals(term.get("direction"))));
        }
        return keys;
    }

    private static Projection projection(CollectionDef def, MaskView view, DataFetchingFieldSelectionSet selection) {
        Set<String> asked = new LinkedHashSet<>();
        for (SelectedField field : selection.getImmediateFields()) {
            asked.add(field.getName());
        }
        Set<String> nulled = new LinkedHashSet<>(view.nulled(def));
        nulled.retainAll(asked);
        Set<String> select = new LinkedHashSet<>(view.selectable(def));
        select.retainAll(asked);
        select.removeAll(nulled);
        return new Projection(select, nulled);
    }

    private static Map<String, Object> row(CollectionDef def, Document doc, DataFetchingFieldSelectionSet selection) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (SelectedField field : selection.getImmediateFields()) {
            String name = field.getName();
            if (def.declares(name)) {
                Object stored = doc.get(CollectionDef.storedName(name));
                row.put(name, CollectionDef.ID_FIELD.equals(name) && stored != null ? ValueCodec.formatId(stored) : shown(stored));
            }
        }
        return row;
    }

    private static Object shown(Object stored) {
        if (stored instanceof Date date) {
            return Instant.ofEpochMilli(date.getTime()).toString();
        }
        if (stored instanceof ObjectId) {
            return ValueCodec.formatId(stored);
        }
        if (stored instanceof Decimal128 decimal) {
            return decimal.bigDecimalValue().doubleValue();
        }
        return stored;
    }

    /** A refusal of the request shows its message; anything else is logged here and shown as a generic failure. */
    private static final class SafeErrors implements DataFetcherExceptionHandler {
        @Override
        public CompletableFuture<DataFetcherExceptionHandlerResult> handleException(
                DataFetcherExceptionHandlerParameters parameters) {
            Throwable cause = parameters.getException();
            String message = "Internal server error";
            if (cause instanceof RequestException) {
                message = cause.getMessage();
            } else {
                log.error("MongoDB request failed", cause);
            }
            var error = GraphqlErrorBuilder.newError(parameters.getDataFetchingEnvironment()).message(message).build();
            return CompletableFuture.completedFuture(DataFetcherExceptionHandlerResult.newResult().error(error).build());
        }
    }
}
