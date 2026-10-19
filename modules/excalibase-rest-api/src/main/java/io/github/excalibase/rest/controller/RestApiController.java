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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

import static io.github.excalibase.compiler.SqlKeywords.*;

@RestController
// Project-scoped: the project lives in the URL path (`/{projectId}/api/v1/…`),
// so RLS always has a project to enforce against — even for anonymous callers.
// JwtAuthFilter reads the projectId from the path; controller methods don't bind it.
@RequestMapping("/{projectId}/api/v1")
@Validated
public class RestApiController {

    private static final String IDENT_REGEX = "^[A-Za-z_]\\w{0,62}$";

    /** The logic parameters: each is one group of filters, AND- or OR-joined, possibly negated. */
    private static final List<String> LOGIC_PARAMS = List.of("or", "and", "not.or", "not.and");
    private static final Set<String> RESERVED_PARAMS = Set.of("select", "order", "limit", "offset", "or", "and",
            "not.or", "not.and", "on_conflict", "first", "after");
    private static final MediaType SINGULAR_TYPE = MediaType.parseMediaType("application/vnd.pgrst.object+json");
    private static final MediaType CSV_TYPE = MediaType.parseMediaType("text/csv");
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

    public RestApiController(SchemaProvider schemaProvider, NamedParameterJdbcTemplate namedJdbc,
                             TransactionTemplate txTemplate, ObjectMapper mapper,
                             @Value("${app.max-rows:30}") int maxRows) {
        this.schemaProvider = schemaProvider;
        this.namedJdbc = namedJdbc;
        this.txTemplate = txTemplate;
        this.mapper = mapper;
        this.maxRows = maxRows;
    }

    @GetMapping(produces = "application/openapi+json")
    public ResponseEntity<Object> openapi(HttpServletRequest request) {
        var principal = getPrincipal(request);
        var schemaInfo = schemaProvider.resolveSchemaInfo(principal);
        return ResponseEntity.ok(OpenApiGenerator.generate(schemaInfo, schemaProvider.resolveAccess(principal),
                schemaProvider.resolveDefaultSchema(principal)));
    }

    @GetMapping("/{table}")
    public ResponseEntity<Object> list(
            @PathVariable String table, @RequestParam(required = false) String select,
            @RequestParam(required = false) String order,
            @RequestParam(required = false) String limit, @RequestParam(required = false) String offset,
            @RequestParam(required = false) Integer first, @RequestParam(required = false) String after,
            @RequestHeader(value = "Prefer", required = false) String prefer,
            @RequestHeader(value = "Accept-Profile", required = false) String acceptProfile,
            @RequestParam Map<String, String> allParams, HttpServletRequest request) {

        var resolved = resolveContext(table, acceptProfile, request);
        if (resolved.refusal() != null) return resolved.refusal();
        var ctx = resolved.context();

        var parsed = parseSelectParams(select, order, allParams);
        String accept = request.getHeader("Accept");
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

    private ResponseEntity<Object> handleSingular(RequestContext ctx, ParsedParams parsed) {
        var compiled = ctx.compiler().compileSelect(new RestQueryCompiler.SelectQuery(ctx.tableKey(), parsed.columns, parsed.filters, parsed.orConditions, parsed.embeds, parsed.orderSpecs, 2, 0, false));
        return executeInTx(compiled, ctx.scope(parsed.allFilters()), rows -> {
            List<?> data = rowsOf(rows.get("body"), responseKeys(ctx, parsed));
            if (data.size() != 1) return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).body(errorBody("not_acceptable", "Expected 1 row, got " + data.size()));
            return ResponseEntity.ok().contentType(SINGULAR_TYPE).body(data.get(0));
        });
    }

    private ResponseEntity<Object> handleCsv(RequestContext ctx, ParsedParams parsed, int limit, int offset) {
        int clamped = Math.clamp(limit, 1, maxRows);
        var compiled = ctx.compiler().compileSelect(new RestQueryCompiler.SelectQuery(ctx.tableKey(), parsed.columns, parsed.filters, parsed.orConditions, parsed.embeds, parsed.orderSpecs, clamped, offset, false));
        List<String> csvCols = parsed.columns.isEmpty() ? new ArrayList<>(ctx.schemaInfo().getColumns(ctx.tableKey())) : parsed.columns;
        return executeInTx(compiled, ctx.scope(parsed.allFilters()), rows -> limit == 0
                ? ResponseEntity.ok().contentType(CSV_TYPE).body((Object) "") : buildCsvResponse(rows, csvCols));
    }

    private ResponseEntity<Object> handleCursor(RequestContext ctx, ParsedParams parsed, int first, String after) {
        int fetchLimit = Math.min(first, maxRows);
        String orderCol = parsed.orderSpecs.isEmpty() ? null : parsed.orderSpecs.get(0).column();
        var query = new RestQueryCompiler.SelectQuery(ctx.tableKey(), parsed.columns, parsed.filters, parsed.orConditions, parsed.embeds, parsed.orderSpecs, fetchLimit + 1, 0, false, after, orderCol);
        return executeInTx(ctx.compiler().compileSelect(query), ctx.scope(parsed.allFilters()), rows -> {
            List<?> data = rowsOf(rows.get("body"), responseKeys(ctx, parsed));
            boolean hasNext = data.size() > fetchLimit;
            return ResponseEntity.ok(Map.of("data", hasNext ? data.subList(0, fetchLimit) : data, "pageInfo", Map.of("hasNextPage", hasNext)));
        });
    }

    /**
     * A page of rows. {@code limit=0} answers no rows, as PostgREST does; with {@code count=exact} the total
     * is still counted, its range {@code *}.
     */
    private ResponseEntity<Object> handlePaginated(RequestContext ctx, ParsedParams parsed, int limit, int offset, String prefer) {
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
                return ResponseEntity.ok().header("Content-Range", range + "/" + total).body((Object) response);
            }
            return ResponseEntity.ok().body((Object) response);
        });
        return (count && resp != null) ? withHeader(resp, HDR_PREFERENCE_APPLIED, "count=exact") : resp;
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/{table}")
    public ResponseEntity<Object> create(
            @PathVariable String table, @RequestBody Object body,
            @RequestHeader(value = "Prefer", required = false) String prefer,
            @RequestHeader(value = "Content-Profile", required = false) String cp,
            @RequestParam(value = "on_conflict", required = false) String onConflict,
            HttpServletRequest request) {

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
        if (compiled == null) return ResponseEntity.badRequest().body(errorBody(BAD_REQUEST, "Body must be JSON object or array"));

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
    @GetMapping("/rpc/{function}")
    public ResponseEntity<Object> rpcGet(
            @PathVariable @Pattern(regexp = IDENT_REGEX) String function,
            @RequestParam Map<String, String> allParams,
            @RequestHeader(value = "Accept-Profile", required = false) String profile,
            HttpServletRequest request) {
        return rpc(function, profile, allParams, null, request);
    }

    /** Any tracked function, with its arguments as a JSON object; query parameters read its rows. */
    @PostMapping("/rpc/{function}")
    public ResponseEntity<Object> rpcPost(
            @PathVariable @Pattern(regexp = IDENT_REGEX) String function,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestParam Map<String, String> allParams,
            @RequestHeader(value = "Content-Profile", required = false) String profile,
            HttpServletRequest request) {
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
    private ResponseEntity<Object> rpc(String function, String profile, Map<String, String> allParams,
                                       Map<String, Object> body, HttpServletRequest request) {
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
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header("Allow", "POST")
                    .body(errorBody("method_not_allowed", function + " changes data: call it with POST"));
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        Map<String, String> readParams = new LinkedHashMap<>(allParams);
        if (body == null) {
            callable.arguments().forEach(argument -> {
                if (readParams.containsKey(argument.name())) {
                    arguments.put(argument.name(), readParams.remove(argument.name()));
                }
            });
        } else {
            arguments.putAll(body);
        }
        Map<String, Object> params = new LinkedHashMap<>();
        String call = FunctionCall.sql(callable, arguments, RlsContext.sessionVariables(), dialect, params);
        var parsed = parseSelectParams(readParams.get("select"), readParams.get("order"), readParams);
        var compiler = new RestQueryCompiler(schemaProvider.resolveSchemaInfo(principal), dialect, schema, maxRows,
                schemaProvider.resolveAccess(principal));
        var query = new RestQueryCompiler.SelectQuery(callable.returnTable(), parsed.columns, parsed.filters,
                parsed.orConditions, parsed.embeds, parsed.orderSpecs,
                Math.clamp(paging("limit", readParams.get("limit"), maxRows), 1, maxRows),
                paging("offset", readParams.get("offset"), 0), false);
        var compiled = compiler.compileFunctionSelect(query, call, params, !callable.returnsSet());
        return executeInTx(compiled, new ErrorScope(dialect, parsed.allFilters()), rows -> {
            List<?> data = rowsOf(rows.get("body"), parsed.columns);
            if (callable.returnsSet()) return ResponseEntity.ok(Map.of("data", data));
            // A JSON tree keeps "data": null; the API's non-null inclusion would drop it from a map.
            ObjectNode single = mapper.createObjectNode();
            single.set("data", data.isEmpty() ? NullNode.getInstance() : mapper.valueToTree(data.getFirst()));
            return ResponseEntity.ok(single);
        });
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

    @PatchMapping("/{table}")
    public ResponseEntity<Object> update(@PathVariable String table, @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Prefer", required = false) String prefer, @RequestHeader(value = "Content-Profile", required = false) String cp,
            @RequestParam Map<String, String> allParams, HttpServletRequest request) {
        return mutate(table, body, prefer, cp, allParams, request);
    }

    @PutMapping("/{table}")
    public ResponseEntity<Object> replace(@PathVariable String table, @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Prefer", required = false) String prefer, @RequestHeader(value = "Content-Profile", required = false) String cp,
            @RequestParam Map<String, String> allParams, HttpServletRequest request) {
        return mutate(table, body, prefer, cp, allParams, request);
    }

    @DeleteMapping("/{table}")
    public ResponseEntity<Object> delete(@PathVariable String table,
            @RequestHeader(value = "Prefer", required = false) String prefer, @RequestHeader(value = "Content-Profile", required = false) String cp,
            @RequestParam Map<String, String> allParams, HttpServletRequest request) {

        var resolved = resolveContext(table, cp, request);
        if (resolved.refusal() != null) return resolved.refusal();
        var ctx = resolved.context();
        var filters = parseFilters(allParams);
        if (filters.isEmpty()) return ResponseEntity.badRequest().body(errorBody(BAD_REQUEST, "At least one filter is required"));

        boolean rollback = preferContains(prefer, PREFER_TX_ROLLBACK);
        Integer maxAffected = parseMaxAffected(prefer);
        return executeDml(ctx.compiler().compileDelete(ctx.tableKey(), filters), ctx.scope(filters), rollback, maxAffected,
                prefer, HttpStatus.OK, null);
    }


    private ResponseEntity<Object> mutate(String table, Map<String, Object> body, String prefer, String cp, Map<String, String> allParams, HttpServletRequest request) {
        var resolved = resolveContext(table, cp, request);
        if (resolved.refusal() != null) return resolved.refusal();
        var ctx = resolved.context();
        var filters = parseFilters(allParams);
        if (filters.isEmpty()) return ResponseEntity.badRequest().body(errorBody(BAD_REQUEST, "At least one filter is required"));

        boolean rollback = preferContains(prefer, PREFER_TX_ROLLBACK);
        Integer maxAffected = parseMaxAffected(prefer);
        return executeDml(ctx.compiler().compileUpdate(ctx.tableKey(), body, filters), ctx.scope(filters), rollback,
                maxAffected, prefer, HttpStatus.OK, null);
    }

    private static ResponseEntity<Object> permissionDenied(String message) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(PermissionErrors.restBody(PermissionErrors.PERMISSION_DENIED, message));
    }

    private static ResponseEntity<Object> checkFailed(PermissionCheckFailedException failed) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(PermissionErrors.restBody(failed.code(), failed.getMessage()));
    }

    /** A permission expression that cannot apply to this request: its code, never a guessed answer. */
    @ExceptionHandler(PermissionEvaluationException.class)
    public ResponseEntity<Object> permissionEvaluationFailed(PermissionEvaluationException e) {
        return ResponseEntity.badRequest().body(PermissionErrors.restBody(e.code(), e.getMessage()));
    }

    /** A request the engine refused before it ran: a column it may not write, a malformed value. */
    @ExceptionHandler(DataErrorException.class)
    public ResponseEntity<Object> dataError(DataErrorException e) {
        return RestErrors.response(e.error());
    }

    /** A request naming a column, operator or shape this caller's schema does not have. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Object> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(errorBody(BAD_REQUEST, e.getMessage()));
    }

    @FunctionalInterface
    private interface QueryResultHandler {
        ResponseEntity<Object> handle(Map<String, Object> rows);
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

    private ResponseEntity<Object> executeInTx(RestQueryCompiler.CompiledResult compiled, ErrorScope scope,
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

    private ResponseEntity<Object> executeDml(RestQueryCompiler.CompiledResult compiled, ErrorScope scope, boolean rollback,
                                              Integer maxAffected, String prefer, HttpStatus successStatus, String table) {
        return txTemplate.execute(status -> {
            try {
                boolean represent = maxAffected != null || preferContains(prefer, PREFER_REPRESENTATION);
                String json = executeDmlQuery(compiled, represent);
                ResponseEntity<Object> overflow = checkMaxAffected(json, maxAffected, status);
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
                    return ResponseEntity.badRequest().body(errorBody(BAD_REQUEST, refused.getMessage()));
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

    private ResponseEntity<Object> checkMaxAffected(String json, Integer maxAffected, TransactionStatus status) {
        if (maxAffected == null || json == null) return null;
        List<?> affected = parseJsonList(json);
        if (affected.size() > maxAffected) {
            status.setRollbackOnly();
            return ResponseEntity.badRequest().body(errorBody(BAD_REQUEST, "Affected rows exceed max-affected limit"));
        }
        return null;
    }

    private ResponseEntity<Object> buildDmlResponse(String json, String prefer, HttpStatus successStatus,
                                                    String table, boolean rollback, List<String> keys) {
        List<String> applied = new ArrayList<>();
        if (rollback) applied.add(PREFER_TX_ROLLBACK);
        boolean returnRepresentation = preferContains(prefer, PREFER_REPRESENTATION);
        ResponseEntity<Object> resp;
        if (returnRepresentation) {
            applied.add(PREFER_REPRESENTATION);
            var builder = ResponseEntity.status(successStatus);
            if (successStatus == HttpStatus.CREATED && table != null) {
                builder.header("Location", "/api/v1/" + table);
            }
            // A written row the role may not read is not represented: an empty array, never the row.
            resp = builder.body(Map.of("data", json == null ? List.of() : parseJson(json, keys)));
        } else {
            resp = ResponseEntity.status(successStatus).body(null);
        }
        return applied.isEmpty() ? resp : withHeader(resp, HDR_PREFERENCE_APPLIED, String.join(", ", applied));
    }

    private ResponseEntity<Object> buildCsvResponse(Map<String, Object> rows, List<String> cols) {
        List<JsonNode> data = parseJsonList(rows.get("body"));
        if (data.isEmpty()) return ResponseEntity.ok().contentType(CSV_TYPE).body((Object) "");
        StringBuilder csv = new StringBuilder();
        csv.append(String.join(",", cols)).append("\n");
        for (JsonNode row : data) {
            csv.append(cols.stream()
                    .map(col -> csvEscape(csvValue(row.get(col))))
                    .collect(Collectors.joining(","))).append("\n");
        }
        return ResponseEntity.ok().contentType(CSV_TYPE).body((Object) csv.toString());
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
    private record Resolved(RequestContext context, ResponseEntity<Object> refusal) {}

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

    private ParsedParams parseSelectParams(String select, String order, Map<String, String> allParams) {
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
    private Resolved resolveContext(String table, String profileHeader, HttpServletRequest request) {
        var principal = getPrincipal(request);
        String schema = resolveSchema(profileHeader, principal);
        if (schema == null) return new Resolved(null, notFound());
        String tableKey = schema + DOT + table;
        var schemaInfo = schemaProvider.resolveSchemaInfo(principal);
        boolean insertOnly = schemaInfo.isWriteOnlyTable(tableKey) && "POST".equals(request.getMethod());
        if (!schemaInfo.hasTable(tableKey) && !insertOnly) return new Resolved(null, notFound());
        TableAccess access = schemaProvider.resolveAccess(principal);
        if (!permitsMethod(access, tableKey, request)) {
            return new Resolved(null, permissionDenied(request.getMethod() + " is not permitted on " + table));
        }
        SqlDialect dialect = schemaProvider.resolveDialect(principal);
        var compiler = new RestQueryCompiler(schemaInfo, dialect, schema, maxRows, access);
        return new Resolved(new RequestContext(tableKey, compiler, schemaInfo, access, dialect), null);
    }

    /**
     * Maps the HTTP method onto the operation it performs. A POST carrying
     * {@code Prefer: resolution=merge-duplicates} upserts, which rewrites existing
     * rows, so it needs UPDATE as well as INSERT.
     */
    private boolean permitsMethod(TableAccess access, String tableKey, HttpServletRequest request) {
        String prefer = request.getHeader("Prefer");
        return switch (request.getMethod()) {
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

    private static ResponseEntity<Object> withHeader(ResponseEntity<Object> resp, String name, String value) {
        return ResponseEntity.status(resp.getStatusCode()).headers(resp.getHeaders()).header(name, value).body(resp.getBody());
    }

    private static ResponseEntity<Object> notFound() { return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody("not_found", "Not found")); }

    private Principal getPrincipal(HttpServletRequest request) { return (Principal) request.getAttribute(SecurityConstants.PRINCIPAL_ATTR); }

    private List<RestQueryCompiler.FilterSpec> parseFilters(Map<String, String> allParams) {
        List<RestQueryCompiler.FilterSpec> filters = new ArrayList<>();
        for (var entry : allParams.entrySet()) {
            if (RESERVED_PARAMS.contains(entry.getKey())) continue;
            var parsed = FilterParser.parse(entry.getKey(), entry.getValue());
            filters.add(new RestQueryCompiler.FilterSpec(parsed.column(), parsed.operator(), parsed.value(), parsed.negated()));
        }
        return filters;
    }

    /** Each of {@code or}, {@code and}, {@code not.or} and {@code not.and} present, as one logic group. */
    private List<RestQueryCompiler.OrCondition> parseOrConditions(Map<String, String> allParams) {
        List<RestQueryCompiler.OrCondition> groups = new ArrayList<>();
        for (String name : LOGIC_PARAMS) {
            String value = allParams.get(name);
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
