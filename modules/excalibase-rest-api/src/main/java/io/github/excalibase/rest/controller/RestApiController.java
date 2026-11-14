package io.github.excalibase.rest.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.excalibase.SqlDialect;
import io.github.excalibase.json.JsonFieldOrder;
import io.github.excalibase.errors.DataErrorException;
import io.github.excalibase.rest.compiler.RestQueryCompiler;
import io.github.excalibase.rest.service.OpenApiGenerator;
import io.github.excalibase.rest.parser.FilterParser;
import io.github.excalibase.rest.parser.OrderParser;
import io.github.excalibase.rest.parser.SelectParser;
import io.github.excalibase.permissions.PermissionEvaluationException;
import io.github.excalibase.compiler.FunctionCall;
import io.github.excalibase.schema.ExposedFunction;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.schema.SchemaProvider;
import io.github.excalibase.schema.TableAccess;
import io.github.excalibase.security.PermissionCheckFailedException;
import io.github.excalibase.security.PermissionErrors;
import io.github.excalibase.security.Principal;
import io.github.excalibase.security.RlsContext;
import io.github.excalibase.security.RlsOp;
import io.github.excalibase.security.SecurityConstants;
import io.github.excalibase.http.Accepts;
import io.github.excalibase.http.Reply;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Error;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Patch;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.stream.Collectors;

import static io.github.excalibase.compiler.SqlKeywords.*;

// Project-scoped: the project lives in the URL path (`/{projectId}/api/v1/…`),
// so RLS always has a project to enforce against — even for anonymous callers.
// JwtAuthFilter reads the projectId from the path; controller methods don't bind it.
@Controller("/{projectId}/api/v1")
@Produces({MediaType.APPLICATION_JSON, "text/csv", "application/vnd.pgrst.object+json", MediaType.ALL})
@ExecuteOn(TaskExecutors.BLOCKING)
public class RestApiController {

    private static final String IDENT_REGEX = "^[A-Za-z_]\\w{0,62}$";

    /** The logic parameters: each is one group of filters, AND- or OR-joined, possibly negated. */
    private static final List<String> LOGIC_PARAMS = List.of("or", "and", "not.or", "not.and");
    private static final Set<String> RESERVED_PARAMS = Set.of("select", "order", "limit", "offset", "or", "and",
            "not.or", "not.and", "on_conflict", "first", "after");
    private static final MediaType SINGULAR_TYPE = MediaType.of("application/vnd.pgrst.object+json");
    private static final MediaType CSV_TYPE = MediaType.of("text/csv");
    private static final String KEY_ERROR = "error";
    private static final String BAD_REQUEST = "bad_request";
    private static final String PREFER_TX_ROLLBACK = "tx=rollback";
    private static final String PREFER_REPRESENTATION = "return=representation";
    private static final String HDR_PREFERENCE_APPLIED = "Preference-Applied";

    private final SchemaProvider schemaProvider;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final TransactionTemplate txTemplate;
    private final ObjectMapper mapper;
    private final int maxRows;
    private final boolean capNestedLists;

    public RestApiController(SchemaProvider schemaProvider, NamedParameterJdbcTemplate namedJdbc,
                             TransactionTemplate txTemplate, ObjectMapper mapper,
                             @Value("${app.max-rows:30}") int maxRows,
                             @Value("${app.cap-nested-lists:false}") boolean capNestedLists) {
        this.capNestedLists = capNestedLists;
        this.schemaProvider = schemaProvider;
        this.namedJdbc = namedJdbc;
        this.txTemplate = txTemplate;
        this.mapper = mapper;
        this.maxRows = maxRows;
    }

    @Get(produces = "application/openapi+json")
    public HttpResponse<Object> openapi(HttpRequest<?> request) {
        if (!Accepts.includes(request, "application", "openapi+json")) {
            throw new HttpStatusException(HttpStatus.NOT_ACCEPTABLE, "Only application/openapi+json is produced");
        }
        var principal = getPrincipal(request);
        var schemaInfo = schemaProvider.resolveSchemaInfo(principal);
        return Reply.ok(OpenApiGenerator.generate(schemaInfo, schemaProvider.resolveAccess(principal),
                schemaProvider.resolveDefaultSchema(principal))).contentType(MediaType.of("application/openapi+json"));
    }

    @Get("/{table}")
    public HttpResponse<Object> list(
            @PathVariable String table, @QueryValue @Nullable String select,
            @QueryValue @Nullable String order,
            @QueryValue @Nullable String limit, @QueryValue @Nullable String offset,
            @QueryValue @Nullable Integer first, @QueryValue @Nullable String after,
            @Header("Prefer") @Nullable String prefer,
            @Header("Accept-Profile") @Nullable String acceptProfile,
            HttpRequest<?> request) {
        Map<String, List<String>> allParams = allParams(request);

        var resolved = resolveContext(table, acceptProfile, request);
        if (resolved.refusal() != null) return resolved.refusal();
        var ctx = resolved.context();

        var parsed = parseSelectParams(select, order, allParams);
        String accept = request.getHeaders().get("Accept");
        int rowLimit = paging("limit", limit, maxRows);
        int rowOffset = paging("offset", offset, 0);

        if (accept != null && accept.contains("vnd.pgrst.object+json")) {
            return handleSingular(ctx, parsed);
        }
        if (accept != null && accept.contains("text/csv")) {
            return handleCsv(ctx, parsed, rowLimit, rowOffset);
        }
        if (first != null) {
            return handleCursor(ctx, parsed, first, after);
        }
        return handlePaginated(ctx, parsed, rowLimit, rowOffset, prefer);
    }

    private HttpResponse<Object> handleSingular(RequestContext ctx, ParsedParams parsed) {
        var compiled = ctx.compiler().compileSelect(new RestQueryCompiler.SelectQuery(ctx.tableKey(), parsed.columns, parsed.filters, parsed.orConditions, parsed.embeds, parsed.orderSpecs, 2, 0, false));
        return executeInTx(compiled, ctx.scope(parsed.allFilters()), rows -> {
            List<?> data = rowsOf(rows.get("body"), responseKeys(ctx, parsed));
            if (data.size() != 1) {
                return Reply.status(HttpStatus.NOT_ACCEPTABLE).contentType(SINGULAR_TYPE)
                        .body(errorBody("not_acceptable", "Expected 1 row, got " + data.size()));
            }
            return Reply.ok().contentType(SINGULAR_TYPE).body(data.get(0));
        });
    }

    private HttpResponse<Object> handleCsv(RequestContext ctx, ParsedParams parsed, int limit, int offset) {
        int clamped = Math.clamp(limit, 1, maxRows);
        var compiled = ctx.compiler().compileSelect(new RestQueryCompiler.SelectQuery(ctx.tableKey(), parsed.columns, parsed.filters, parsed.orConditions, parsed.embeds, parsed.orderSpecs, clamped, offset, false));
        List<String> csvCols = parsed.columns.isEmpty() ? new ArrayList<>(ctx.schemaInfo().getColumns(ctx.tableKey())) : parsed.columns;
        return executeInTx(compiled, ctx.scope(parsed.allFilters()), rows -> limit == 0
                ? Reply.ok().contentType(CSV_TYPE).body((Object) "") : buildCsvResponse(rows, csvCols));
    }

    private HttpResponse<Object> handleCursor(RequestContext ctx, ParsedParams parsed, int first, String after) {
        int fetchLimit = Math.min(first, maxRows);
        String orderCol = parsed.orderSpecs.isEmpty() ? null : parsed.orderSpecs.get(0).column();
        var query = new RestQueryCompiler.SelectQuery(ctx.tableKey(), parsed.columns, parsed.filters, parsed.orConditions, parsed.embeds, parsed.orderSpecs, fetchLimit + 1, 0, false, after, orderCol);
        return executeInTx(ctx.compiler().compileSelect(query), ctx.scope(parsed.allFilters()), rows -> {
            List<?> data = rowsOf(rows.get("body"), responseKeys(ctx, parsed));
            boolean hasNext = data.size() > fetchLimit;
            return Reply.ok(Map.of("data", hasNext ? data.subList(0, fetchLimit) : data, "pageInfo", Map.of("hasNextPage", hasNext)));
        });
    }

    /**
     * A page of rows. {@code limit=0} answers no rows, as PostgREST does; with {@code count=exact} the total
     * is still counted, its range {@code *}.
     */
    private HttpResponse<Object> handlePaginated(RequestContext ctx, ParsedParams parsed, int limit, int offset, String prefer) {
        boolean count = preferContains(prefer, "count=exact");
        if (count && !ctx.access().allowsAggregations(ctx.tableKey())) {
            return permissionDenied("Counting rows of " + ctx.tableKey() + " is not permitted");
        }
        // limit=0 still runs the query, so its filters are checked; its rows are not answered.
        int clamped = Math.clamp(limit, 1, maxRows);
        var compiled = ctx.compiler().compileSelect(new RestQueryCompiler.SelectQuery(ctx.tableKey(), parsed.columns, parsed.filters, parsed.orConditions, parsed.embeds, parsed.orderSpecs, clamped, offset, count));
        var resp = executeInTx(compiled, ctx.scope(parsed.allFilters()), rows -> {
            var response = new LinkedHashMap<String, Object>();
            response.put("data", limit == 0 ? List.of() : rowsOf(rows.get("body"), responseKeys(ctx, parsed)));
            if (count && rows.containsKey("total_count")) {
                long total = ((Number) rows.get("total_count")).longValue();
                response.put("pagination", Map.of("total", total, "limit", limit == 0 ? 0 : clamped, "offset", offset));
                String range = limit == 0 ? "*" : offset + "-" + (offset + clamped - 1);
                return Reply.ok().header("Content-Range", range + "/" + total).body((Object) response);
            }
            return Reply.ok().body((Object) response);
        });
        return (count && resp != null) ? withHeader(resp, HDR_PREFERENCE_APPLIED, "count=exact") : resp;
    }

    @SuppressWarnings("unchecked")
    @Post("/{table}")
    public HttpResponse<Object> create(
            @PathVariable String table, @Body Object body,
            @Header("Prefer") @Nullable String prefer,
            @Header("Content-Profile") @Nullable String cp,
            @QueryValue("on_conflict") @Nullable String onConflict,
            HttpRequest<?> request) {

        var resolved = resolveContext(table, cp, request);
        if (resolved.refusal() != null) return resolved.refusal();
        var ctx = resolved.context();
        boolean rollback = preferContains(prefer, PREFER_TX_ROLLBACK);
        boolean mergeDuplicates = preferContains(prefer, "resolution=merge-duplicates");
        List<String> conflictColumns = conflictColumns(ctx, onConflict);

        RestQueryCompiler.CompiledResult compiled = switch (body) {
            case List<?> list -> ctx.compiler().compileBulkInsert(ctx.tableKey(), (List<Map<String, Object>>) list);
            case Map<?, ?> map -> {
                var row = (Map<String, Object>) map;
                yield mergeDuplicates
                    ? ctx.compiler().compileUpsert(ctx.tableKey(), row, conflictColumns)
                    : ctx.compiler().compileInsert(ctx.tableKey(), row);
            }
            default -> null;
        };
        if (compiled == null) return Reply.badRequest().body(errorBody(BAD_REQUEST, "Body must be JSON object or array"));

        return executeDml(compiled, ctx.scope(List.of()), rollback, null, prefer, HttpStatus.CREATED, table);
    }

    /** The columns an upsert merges on: {@code on_conflict}'s, each a column of the table, else the primary key. */
    private static List<String> conflictColumns(RequestContext ctx, String onConflict) {
        if (onConflict == null || onConflict.isBlank()) return ctx.schemaInfo().getPrimaryKeys(ctx.tableKey());
        List<String> columns = Arrays.stream(onConflict.split(",")).map(String::trim).toList();
        Set<String> known = ctx.schemaInfo().getColumns(ctx.tableKey());
        columns.stream().filter(column -> !known.contains(column)).findFirst().ifPresent(column -> {
            throw new IllegalArgumentException("Unknown on_conflict column '" + column + "' on " + ctx.tableKey());
        });
        return columns;
    }

    /** A query function with its arguments as query parameters; the other parameters read its rows. */
    @Get("/rpc/{function}")
    public HttpResponse<Object> rpcGet(
            @PathVariable String function,
            @Header("Accept-Profile") @Nullable String profile,
            HttpRequest<?> request) {
        Map<String, List<String>> allParams = allParams(request);
        return rpc(function, profile, allParams, null, request);
    }

    /** Any tracked function, with its arguments as a JSON object; query parameters read its rows. */
    @Post("/rpc/{function}")
    public HttpResponse<Object> rpcPost(
            @PathVariable String function,
            @Body @Nullable Map<String, Object> body,
            @Header("Content-Profile") @Nullable String profile,
            HttpRequest<?> request) {
        Map<String, List<String>> allParams = allParams(request);
        return rpc(function, profile, allParams, body == null ? Map.of() : body, request);
    }

    /**
     * Calls a tracked function the caller may call (404 otherwise, as for a function that does not
     * exist); a mutation function answers only POST. Its rows obey the return table's select permission,
     * and the request's filters, select, order, limit and offset apply to them. The function body runs
     * with the database privileges of the engine's connection, or of its owner when SECURITY DEFINER.
     *
     * @param body the arguments of a POST, or null for a GET, whose arguments are query parameters
     */
    private HttpResponse<Object> rpc(String function, String profile, Map<String, List<String>> allParams,
                                       Map<String, Object> body, HttpRequest<?> request) {
        requireIdentifier(function);
        var principal = getPrincipal(request);
        String schema = resolveSchema(profile, principal);
        if (schema == null) return notFound();
        Optional<ExposedFunction> found = schemaProvider.resolveFunctions(principal).named(schema + DOT + function);
        var dialect = schemaProvider.resolveDialect(principal);
        if (!dialect.supportsFunctions()) {
            throw new IllegalArgumentException("Functions are not supported on " + dialect.databaseName());
        }
        if (found.isEmpty()) return notFound();
        ExposedFunction callable = found.get();
        if (body == null && callable.operation() == ExposedFunction.Operation.MUTATION) {
            return Reply.status(HttpStatus.METHOD_NOT_ALLOWED).header("Allow", "POST")
                    .body(errorBody("method_not_allowed", function + " changes data: call it with POST"));
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        Map<String, List<String>> readParams = new LinkedHashMap<>(allParams);
        if (body == null) {
            callable.arguments().forEach(argument -> {
                if (readParams.containsKey(argument.name())) {
                    arguments.put(argument.name(), readParams.remove(argument.name()).getFirst());
                }
            });
        } else {
            arguments.putAll(body);
        }
        Map<String, Object> params = new LinkedHashMap<>();
        String call = FunctionCall.sql(callable, arguments, RlsContext.sessionVariables(), dialect, params);
        var parsed = parseSelectParams(first(readParams, "select"), first(readParams, "order"), readParams);
        var compiler = new RestQueryCompiler(schemaProvider.resolveSchemaInfo(principal), dialect, schema, maxRows,
                schemaProvider.resolveAccess(principal), capNestedLists);
        var query = new RestQueryCompiler.SelectQuery(callable.returnTable(), parsed.columns, parsed.filters,
                parsed.orConditions, parsed.embeds, parsed.orderSpecs,
                Math.clamp(paging("limit", first(readParams, "limit"), maxRows), 1, maxRows),
                paging("offset", first(readParams, "offset"), 0), false);
        var compiled = compiler.compileFunctionSelect(query, call, params, !callable.returnsSet());
        return executeInTx(compiled, new ErrorScope(dialect, parsed.allFilters()), rows -> {
            List<?> data = rowsOf(rows.get("body"), parsed.columns);
            if (callable.returnsSet()) return Reply.ok(Map.of("data", data));
            // A JSON tree keeps "data": null; the API's non-null inclusion would drop it from a map.
            ObjectNode single = mapper.createObjectNode();
            single.set("data", data.isEmpty() ? NullNode.getInstance() : mapper.valueToTree(data.getFirst()));
            return Reply.ok(single);
        });
    }

    /** An rpc name is spliced into SQL: anything but an identifier is refused, as an unhandled failure. */
    private static void requireIdentifier(String name) {
        if (!name.matches(IDENT_REGEX)) {
            throw new InvalidFunctionNameException();
        }
    }

    /** A {@code limit} or {@code offset}: a non-negative integer, else the request is refused. */
    private static int paging(String name, String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed >= 0) return parsed;
        } catch (NumberFormatException _) {
            // refused below with the parameter named, never the parser's message
        }
        throw new IllegalArgumentException("Invalid " + name + ": must be a non-negative integer");
    }

    @Patch("/{table}")
    public HttpResponse<Object> update(@PathVariable String table, @Body Map<String, Object> body,
            @Header("Prefer") @Nullable String prefer, @Header("Content-Profile") @Nullable String cp,
            HttpRequest<?> request) {
        Map<String, List<String>> allParams = allParams(request);
        return mutate(table, body, prefer, cp, allParams, request);
    }

    @Put("/{table}")
    public HttpResponse<Object> replace(@PathVariable String table, @Body Map<String, Object> body,
            @Header("Prefer") @Nullable String prefer, @Header("Content-Profile") @Nullable String cp,
            HttpRequest<?> request) {
        Map<String, List<String>> allParams = allParams(request);
        return mutate(table, body, prefer, cp, allParams, request);
    }

    @Delete("/{table}")
    public HttpResponse<Object> delete(@PathVariable String table,
            @Header("Prefer") @Nullable String prefer, @Header("Content-Profile") @Nullable String cp,
            HttpRequest<?> request) {
        Map<String, List<String>> allParams = allParams(request);

        var resolved = resolveContext(table, cp, request);
        if (resolved.refusal() != null) return resolved.refusal();
        var ctx = resolved.context();
        var logic = refuseLogicGroups(allParams);
        if (logic != null) return logic;
        var filters = parseFilters(allParams);
        if (filters.isEmpty()) return Reply.badRequest().body(errorBody(BAD_REQUEST, "At least one filter is required"));

        boolean rollback = preferContains(prefer, PREFER_TX_ROLLBACK);
        Integer maxAffected = parseMaxAffected(prefer);
        return executeDml(ctx.compiler().compileDelete(ctx.tableKey(), filters), ctx.scope(filters), rollback, maxAffected,
                prefer, HttpStatus.OK, null);
    }


    private HttpResponse<Object> mutate(String table, Map<String, Object> body, String prefer, String cp, Map<String, List<String>> allParams, HttpRequest<?> request) {
        var resolved = resolveContext(table, cp, request);
        if (resolved.refusal() != null) return resolved.refusal();
        var ctx = resolved.context();
        var logic = refuseLogicGroups(allParams);
        if (logic != null) return logic;
        var filters = parseFilters(allParams);
        if (filters.isEmpty()) return Reply.badRequest().body(errorBody(BAD_REQUEST, "At least one filter is required"));

        boolean rollback = preferContains(prefer, PREFER_TX_ROLLBACK);
        Integer maxAffected = parseMaxAffected(prefer);
        return executeDml(ctx.compiler().compileUpdate(ctx.tableKey(), body, filters), ctx.scope(filters), rollback,
                maxAffected, prefer, HttpStatus.OK, null);
    }

    private static HttpResponse<Object> permissionDenied(String message) {
        return Reply.status(HttpStatus.FORBIDDEN)
                .body(PermissionErrors.restBody(PermissionErrors.PERMISSION_DENIED, message));
    }

    private static HttpResponse<Object> checkFailed(PermissionCheckFailedException failed) {
        return Reply.status(HttpStatus.FORBIDDEN)
                .body(PermissionErrors.restBody(failed.code(), failed.getMessage()));
    }

    /** A permission expression that cannot apply to this request: its code, never a guessed answer. */
    @Error(exception = PermissionEvaluationException.class)
    public HttpResponse<Object> permissionEvaluationFailed(PermissionEvaluationException e) {
        return Reply.badRequest().body(PermissionErrors.restBody(e.code(), e.getMessage()));
    }

    /** A request the engine refused before it ran: a column it may not write, a malformed value. */
    @Error(exception = DataErrorException.class)
    public HttpResponse<Object> dataError(DataErrorException e) {
        return RestErrors.response(e.error());
    }

    /** A request naming a column, operator or shape this caller's schema does not have. */
    @Error(exception = IllegalArgumentException.class)
    public HttpResponse<Object> badRequest(IllegalArgumentException e) {
        return Reply.badRequest().body(errorBody(BAD_REQUEST, e.getMessage()));
    }

    @FunctionalInterface
    private interface QueryResultHandler {
        HttpResponse<Object> handle(Map<String, Object> rows);
    }

    /**
     * What a failure is judged against: the dialect that reads its error, the filters that may name its column;
     * and the order a written row's fields are represented in.
     */
    private record ErrorScope(SqlDialect dialect, List<RestQueryCompiler.FilterSpec> filters, List<String> keys) {
        ErrorScope(SqlDialect dialect, List<RestQueryCompiler.FilterSpec> filters) {
            this(dialect, filters, List.of());
        }
    }

    private HttpResponse<Object> executeInTx(RestQueryCompiler.CompiledResult compiled, ErrorScope scope,
                                               QueryResultHandler handler) {
        return txTemplate.execute(status -> {
            try {
                var rows = namedJdbc.queryForMap(compiled.sql(), new MapSqlParameterSource(compiled.params()));
                return handler.handle(rows);
            } catch (Exception e) {
                status.setRollbackOnly();
                return RestErrors.of(e, scope.dialect(), scope.filters(), "Query execution failed");
            }
        });
    }

    private HttpResponse<Object> executeDml(RestQueryCompiler.CompiledResult compiled, ErrorScope scope, boolean rollback,
                                              Integer maxAffected, String prefer, HttpStatus successStatus, String table) {
        return txTemplate.execute(status -> {
            try {
                boolean represent = maxAffected != null || preferContains(prefer, PREFER_REPRESENTATION);
                String json = executeDmlQuery(compiled, represent);
                HttpResponse<Object> overflow = checkMaxAffected(json, maxAffected, status);
                if (overflow != null) return overflow;
                if (rollback) status.setRollbackOnly();
                return buildDmlResponse(json, prefer, successStatus, table, rollback, scope.keys());
            } catch (Exception e) {
                Optional<PermissionCheckFailedException> failed = PermissionCheckFailedException.find(e);
                if (failed.isPresent()) {
                    status.setRollbackOnly();
                    return checkFailed(failed.get());
                }
                if (e instanceof IllegalArgumentException refused) {
                    status.setRollbackOnly();
                    return Reply.badRequest().body(errorBody(BAD_REQUEST, refused.getMessage()));
                }
                status.setRollbackOnly();
                return RestErrors.of(e, scope.dialect(), scope.filters(), "Mutation execution failed");
            }
        });
    }

    private String executeDmlQuery(RestQueryCompiler.CompiledResult compiled, boolean represent) {
        if (compiled.write() != null) return compiled.write().run(namedJdbc, represent);
        List<String> rows = namedJdbc.queryForList(compiled.sql(), new MapSqlParameterSource(compiled.params()), String.class);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private HttpResponse<Object> checkMaxAffected(String json, Integer maxAffected, TransactionStatus status) {
        if (maxAffected == null || json == null) return null;
        List<?> affected = parseJsonList(json);
        if (affected.size() > maxAffected) {
            status.setRollbackOnly();
            return Reply.badRequest().body(errorBody(BAD_REQUEST, "Affected rows exceed max-affected limit"));
        }
        return null;
    }

    private HttpResponse<Object> buildDmlResponse(String json, String prefer, HttpStatus successStatus,
                                                    String table, boolean rollback, List<String> keys) {
        List<String> applied = new ArrayList<>();
        if (rollback) applied.add(PREFER_TX_ROLLBACK);
        boolean returnRepresentation = preferContains(prefer, PREFER_REPRESENTATION);
        HttpResponse<Object> resp;
        if (returnRepresentation) {
            applied.add(PREFER_REPRESENTATION);
            var builder = Reply.status(successStatus);
            if (successStatus == HttpStatus.CREATED && table != null) {
                builder.header("Location", "/api/v1/" + table);
            }
            // A written row the role may not read is not represented: an empty array, never the row.
            resp = builder.body(Map.of("data", json == null ? List.of() : parseJson(json, keys)));
        } else {
            resp = Reply.status(successStatus).body(null);
        }
        return applied.isEmpty() ? resp : withHeader(resp, HDR_PREFERENCE_APPLIED, String.join(", ", applied));
    }

    private HttpResponse<Object> buildCsvResponse(Map<String, Object> rows, List<String> cols) {
        List<JsonNode> data = parseJsonList(rows.get("body"));
        if (data.isEmpty()) return Reply.ok().contentType(CSV_TYPE).body((Object) "");
        StringBuilder csv = new StringBuilder();
        csv.append(String.join(",", cols)).append("\n");
        for (JsonNode row : data) {
            csv.append(cols.stream()
                    .map(col -> csvEscape(csvValue(row.get(col))))
                    .collect(Collectors.joining(","))).append("\n");
        }
        return Reply.ok().contentType(CSV_TYPE).body((Object) csv.toString());
    }

    /** A NULL is an empty field; text is its own characters, anything else its JSON. */
    private static String csvValue(JsonNode value) {
        if (value == null || value.isNull()) return "";
        return value.isValueNode() ? value.asText() : value.toString();
    }

    private static String csvEscape(String value) {
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }


    private record RequestContext(String tableKey, RestQueryCompiler compiler, SchemaInfo schemaInfo,
                                  TableAccess access, SqlDialect dialect) {
        ErrorScope scope(List<RestQueryCompiler.FilterSpec> filters) {
            return new ErrorScope(dialect, filters, new ArrayList<>(schemaInfo.getColumns(tableKey)));
        }
    }

    /** The request's table, or the refusal the caller gets instead. */
    private record Resolved(RequestContext context, HttpResponse<Object> refusal) {}

    private record ParsedParams(List<String> columns, List<RestQueryCompiler.FilterSpec> filters,
                                List<RestQueryCompiler.OrCondition> orConditions, List<RestQueryCompiler.EmbedSpec> embeds,
                                List<RestQueryCompiler.OrderBySpec> orderSpecs) {
        /** Every filter the request names, the {@code or} conditions included. */
        List<RestQueryCompiler.FilterSpec> allFilters() {
            List<RestQueryCompiler.FilterSpec> all = new ArrayList<>(filters);
            orConditions.forEach(or -> all.addAll(or.conditions()));
            return all;
        }
    }

    private ParsedParams parseSelectParams(String select, String order, Map<String, List<String>> allParams) {
        var selectResult = SelectParser.parse(select);
        return new ParsedParams(
            selectResult.columns(),
            parseFilters(allParams),
            parseOrConditions(allParams),
            selectResult.embeds().stream().map(RestApiController::toCompilerEmbed).toList(),
            OrderParser.parse(order).stream().map(o -> new RestQueryCompiler.OrderBySpec(o.column(), o.direction(), o.nulls())).toList());
    }

    /**
     * Resolves the table this request addresses. A table the caller cannot select is absent from
     * their schema and answers 404, like one that does not exist, except to a POST when the caller may
     * insert into it; on a table they can see, a method whose operation they hold no permission for
     * answers 403 {@code permission_denied}.
     */
    private Resolved resolveContext(String table, String profileHeader, HttpRequest<?> request) {
        var principal = getPrincipal(request);
        String schema = resolveSchema(profileHeader, principal);
        if (schema == null) return new Resolved(null, notFound());
        String tableKey = schema + DOT + table;
        var schemaInfo = schemaProvider.resolveSchemaInfo(principal);
        boolean insertOnly = schemaInfo.isWriteOnlyTable(tableKey) && "POST".equals(request.getMethodName());
        if (!schemaInfo.hasTable(tableKey) && !insertOnly) return new Resolved(null, notFound());
        TableAccess access = schemaProvider.resolveAccess(principal);
        if (!permitsMethod(access, tableKey, request)) {
            return new Resolved(null, permissionDenied(request.getMethodName() + " is not permitted on " + table));
        }
        SqlDialect dialect = schemaProvider.resolveDialect(principal);
        var compiler = new RestQueryCompiler(schemaInfo, dialect, schema, maxRows, access, capNestedLists);
        return new Resolved(new RequestContext(tableKey, compiler, schemaInfo, access, dialect), null);
    }

    /**
     * Maps the HTTP method onto the operation it performs. A POST carrying
     * {@code Prefer: resolution=merge-duplicates} upserts, which rewrites existing
     * rows, so it needs UPDATE as well as INSERT.
     */
    private boolean permitsMethod(TableAccess access, String tableKey, HttpRequest<?> request) {
        String prefer = request.getHeaders().get("Prefer");
        return switch (request.getMethodName()) {
            case "POST" -> access.permits(tableKey, RlsOp.INSERT)
                    && (!preferContains(prefer, "resolution=merge-duplicates")
                        || access.permits(tableKey, RlsOp.UPDATE));
            case "PATCH", "PUT" -> access.permits(tableKey, RlsOp.UPDATE);
            case "DELETE" -> access.permits(tableKey, RlsOp.DELETE);
            default -> access.permits(tableKey, RlsOp.SELECT);
        };
    }

    private String resolveSchema(String header, Principal principal) {
        if (header == null || header.isBlank()) return schemaProvider.resolveDefaultSchema(principal);
        String trimmed = header.trim();
        if (!trimmed.matches(IDENT_REGEX)) {
            throw new IllegalArgumentException("Invalid Content-Profile");
        }
        return trimmed;
    }

    private boolean preferContains(String prefer, String token) {
        if (prefer == null) return false;
        for (String part : prefer.split(",")) {
            if (part.trim().equals(token) || part.trim().startsWith(token)) return true;
        }
        return false;
    }

    private Integer parseMaxAffected(String prefer) {
        if (prefer == null) return null;
        for (String part : prefer.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("max-affected=")) {
                try { return Integer.parseInt(trimmed.substring("max-affected=".length())); }
                catch (NumberFormatException _) { return null; }
            }
        }
        return null;
    }

    private static HttpResponse<Object> withHeader(HttpResponse<Object> resp, String name, String value) {
        return Reply.withHeader(resp, name, value);
    }

    private static HttpResponse<Object> notFound() { return Reply.status(HttpStatus.NOT_FOUND).body(errorBody("not_found", "Not found")); }

    private Principal getPrincipal(HttpRequest<?> request) {
        return (Principal) request.getAttribute(SecurityConstants.PRINCIPAL_ATTR).orElse(null);
    }

    /** Every value of each query parameter, in the order the request sent them. */
    private static Map<String, List<String>> allParams(HttpRequest<?> request) {
        Map<String, List<String>> params = new LinkedHashMap<>();
        request.getParameters().forEach((name, values) -> {
            if (!values.isEmpty()) params.put(name, List.copyOf(values));
        });
        return params;
    }

    /** The first value of {@code name}, or null: the parameters that take one value (select, order, limit). */
    private static String first(Map<String, List<String>> params, String name) {
        List<String> values = params.get(name);
        return values == null ? null : values.getFirst();
    }

    private List<RestQueryCompiler.FilterSpec> parseFilters(Map<String, List<String>> allParams) {
        List<RestQueryCompiler.FilterSpec> filters = new ArrayList<>();
        for (var entry : allParams.entrySet()) {
            if (RESERVED_PARAMS.contains(entry.getKey())) continue;
            // Every value is a filter, ANDed: ?created=gte.X&created=lt.Y is a range.
            for (String value : entry.getValue()) {
                var parsed = FilterParser.parse(entry.getKey(), value);
                filters.add(new RestQueryCompiler.FilterSpec(parsed.column(), parsed.operator(), parsed.value(), parsed.negated()));
            }
        }
        return filters;
    }

    /**
     * A write is scoped by plain column filters only. An {@code or=}/{@code and=} group is refused rather than
     * dropped, which would let the write touch more rows than the request asked for.
     */
    private static HttpResponse<Object> refuseLogicGroups(Map<String, List<String>> allParams) {
        for (String name : LOGIC_PARAMS) {
            String value = first(allParams, name);
            if (value != null && !value.isBlank()) {
                return Reply.badRequest().body(errorBody(BAD_REQUEST,
                        "'" + name + "' is not supported on PATCH, PUT or DELETE: filter by column"));
            }
        }
        return null;
    }

    /** Each of {@code or}, {@code and}, {@code not.or} and {@code not.and} present, as one logic group. */
    private List<RestQueryCompiler.OrCondition> parseOrConditions(Map<String, List<String>> allParams) {
        List<RestQueryCompiler.OrCondition> groups = new ArrayList<>();
        for (String name : LOGIC_PARAMS) {
            String value = first(allParams, name);
            if (value == null || value.isBlank()) continue;
            String grouped = value.trim().startsWith("(") ? value : "(" + value + ")";
            boolean negated = name.startsWith("not.");
            boolean and = name.endsWith("and");
            groups.add(new RestQueryCompiler.OrCondition(toCompilerGroup(FilterParser.parseLogic(grouped, and, negated))));
        }
        return groups;
    }

    private static RestQueryCompiler.LogicGroup toCompilerGroup(FilterParser.LogicGroup group) {
        return new RestQueryCompiler.LogicGroup(group.and(), group.negated(),
                group.filters().stream().map(p -> new RestQueryCompiler.FilterSpec(p.column(), p.operator(), p.value(), p.negated())).toList(),
                group.groups().stream().map(RestApiController::toCompilerGroup).toList());
    }

    /**
     * Rows are kept as JSON trees so a NULL column is answered as {@code null}, never dropped by the API's
     * non-null inclusion, and each row's fields are put in {@code keys}' order.
     */
    private Object parseJson(String json, List<String> keys) {
        try {
            JsonNode tree = mapper.readTree(json);
            if (!tree.isArray()) return JsonFieldOrder.ordered(tree, keys);
            List<JsonNode> rows = new ArrayList<>();
            tree.forEach(row -> rows.add(JsonFieldOrder.ordered(row, keys)));
            return rows;
        } catch (Exception _) {
            return json;
        }
    }

    private List<JsonNode> rowsOf(Object body, List<String> keys) {
        return parseJsonList(body).stream().map(row -> JsonFieldOrder.ordered(row, keys)).toList();
    }

    private List<JsonNode> parseJsonList(Object body) {
        if (body == null) return List.of();
        try {
            List<JsonNode> rows = new ArrayList<>();
            mapper.readTree(body.toString()).forEach(rows::add);
            return rows;
        } catch (Exception _) {
            return List.of();
        }
    }

    /** The order a read's fields are answered in: the selected columns, or the table's, then the embeds. */
    private static List<String> responseKeys(RequestContext ctx, ParsedParams parsed) {
        List<String> keys = new ArrayList<>(parsed.columns.isEmpty()
                ? ctx.schemaInfo().getColumns(ctx.tableKey()) : parsed.columns);
        parsed.embeds.forEach(embed -> keys.add(embed.relationName()));
        return keys;
    }

    private static RestQueryCompiler.EmbedSpec toCompilerEmbed(SelectParser.EmbedSpec e) {
        List<RestQueryCompiler.EmbedSpec> children = e.children().stream().map(RestApiController::toCompilerEmbed).toList();
        return new RestQueryCompiler.EmbedSpec(e.relationName(), e.columns(), e.fkHint(), children);
    }

    /** A refusal shaped like every other error the data API answers: a code, a message, and the message as error. */
    private static Map<String, Object> errorBody(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put(KEY_ERROR, message);
        return body;
    }
}
