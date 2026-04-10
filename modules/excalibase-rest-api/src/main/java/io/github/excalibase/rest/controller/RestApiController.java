package io.github.excalibase.rest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.SqlDialect;
import io.github.excalibase.rest.compiler.RestQueryCompiler;
import io.github.excalibase.rest.parser.FilterParser;
import io.github.excalibase.rest.parser.OrderParser;
import io.github.excalibase.rest.parser.SelectParser;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.schema.SchemaProvider;
import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.security.SecurityConstants;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

import static io.github.excalibase.compiler.SqlKeywords.*;

@RestController
@RequestMapping("/api/v1")
public class RestApiController {

    private static final Logger log = LoggerFactory.getLogger(RestApiController.class);
    private static final Set<String> RESERVED_PARAMS = Set.of("select", "order", "limit", "offset", "or", "first", "after");
    private static final MediaType SINGULAR_TYPE = MediaType.parseMediaType("application/vnd.pgrst.object+json");
    private static final MediaType CSV_TYPE = MediaType.parseMediaType("text/csv");
    private static final String RLS_USER_ID = "request.user_id";
    private static final String RLS_PROJECT_ID = "request.project_id";
    private static final String RLS_SET_CONFIG = "SELECT set_config(:key, :val, true)";

    private final SchemaProvider schemaProvider;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final TransactionTemplate txTemplate;
    private final ObjectMapper mapper;
    private final String defaultSchema;
    private final Set<String> allowedSchemas;
    private final int maxRows;
    private final boolean jwtEnabled;

    public RestApiController(SchemaProvider schemaProvider, NamedParameterJdbcTemplate namedJdbc,
                             TransactionTemplate txTemplate, ObjectMapper mapper,
                             @Value("${app.schemas:public}") String schemas,
                             @Value("${app.max-rows:30}") int maxRows,
                             @Value("${app.security.jwt-enabled:false}") boolean jwtEnabled) {
        this.schemaProvider = schemaProvider;
        this.namedJdbc = namedJdbc;
        this.txTemplate = txTemplate;
        this.mapper = mapper;
        this.maxRows = maxRows;
        this.jwtEnabled = jwtEnabled;
        String[] parts = schemas.split(",");
        this.defaultSchema = parts[0].trim();
        this.allowedSchemas = Set.copyOf(Arrays.stream(parts).map(String::trim).toList());
    }

    @GetMapping("/{table}")
    public ResponseEntity<Object> list(
            @PathVariable String table, @RequestParam(required = false) String select,
            @RequestParam(required = false) String order,
            @RequestParam(required = false, defaultValue = "30") int limit,
            @RequestParam(required = false, defaultValue = "0") int offset,
            @RequestParam(required = false) Integer first, @RequestParam(required = false) String after,
            @RequestHeader(value = "Prefer", required = false) String prefer,
            @RequestHeader(value = "Accept-Profile", required = false) String acceptProfile,
            @RequestParam Map<String, String> allParams, HttpServletRequest request) {

        var ctx = resolveContext(table, acceptProfile, request);
        if (ctx == null) return jwtEnabled && getClaims(request) == null ? unauthorized() : notFound();

        var parsed = parseSelectParams(select, order, allParams);
        String accept = request.getHeader("Accept");

        if (accept != null && accept.contains("vnd.pgrst.object+json")) {
            var compiled = ctx.compiler().compileSelect(ctx.tableKey(), parsed.columns, parsed.filters, parsed.orConditions, parsed.embeds, parsed.orderSpecs, 2, 0, false);
            return executeInTx(compiled, ctx.claims(), rows -> {
                List<?> data = parseJsonList(rows.get("body"));
                if (data.size() != 1) return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).body(Map.of("error", "Expected 1 row, got " + data.size()));
                return ResponseEntity.ok().contentType(SINGULAR_TYPE).body(data.get(0));
            });
        }
        if (accept != null && accept.contains("text/csv")) {
            limit = Math.min(Math.max(limit, 1), maxRows);
            var compiled = ctx.compiler().compileSelect(ctx.tableKey(), parsed.columns, parsed.filters, parsed.orConditions, parsed.embeds, parsed.orderSpecs, limit, offset, false);
            List<String> csvCols = parsed.columns.isEmpty() ? new ArrayList<>(ctx.schemaInfo().getColumns(ctx.tableKey())) : parsed.columns;
            return executeInTx(compiled, ctx.claims(), rows -> buildCsvResponse(rows, csvCols));
        }
        if (first != null) {
            int fetchLimit = Math.min(first, maxRows);
            String orderCol = parsed.orderSpecs.isEmpty() ? null : parsed.orderSpecs.get(0).column();
            var q = new RestQueryCompiler.SelectQuery(ctx.tableKey(), parsed.columns, parsed.filters, parsed.orConditions, parsed.embeds, parsed.orderSpecs, fetchLimit + 1, 0, false, after, orderCol);
            return executeInTx(ctx.compiler().compileSelect(q), ctx.claims(), rows -> {
                List<?> data = parseJsonList(rows.get("body"));
                boolean hasNext = data.size() > fetchLimit;
                return ResponseEntity.ok(Map.of("data", hasNext ? data.subList(0, fetchLimit) : data, "pageInfo", Map.of("hasNextPage", hasNext)));
            });
        }

        limit = Math.min(Math.max(limit, 1), maxRows);
        boolean count = preferContains(prefer, "count=exact");
        var compiled = ctx.compiler().compileSelect(ctx.tableKey(), parsed.columns, parsed.filters, parsed.orConditions, parsed.embeds, parsed.orderSpecs, limit, offset, count);
        int finalLimit = limit; int finalOffset = offset;
        var resp = executeInTx(compiled, ctx.claims(), rows -> {
            var response = new LinkedHashMap<String, Object>();
            response.put("data", parseJsonList(rows.get("body")));
            if (count && rows.containsKey("total_count")) {
                long total = ((Number) rows.get("total_count")).longValue();
                response.put("pagination", Map.of("total", total, "limit", finalLimit, "offset", finalOffset));
                return ResponseEntity.ok().header("Content-Range", finalOffset + "-" + (finalOffset + finalLimit - 1) + "/" + total).body((Object) response);
            }
            return ResponseEntity.ok().body((Object) response);
        });
        return count ? withHeader(resp, "Preference-Applied", "count=exact") : resp;
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/{table}")
    public ResponseEntity<Object> create(
            @PathVariable String table, @RequestBody Object body,
            @RequestHeader(value = "Prefer", required = false) String prefer,
            @RequestHeader(value = "Content-Profile", required = false) String cp,
            HttpServletRequest request) {

        var ctx = resolveContext(table, cp, request);
        if (ctx == null) return jwtEnabled && getClaims(request) == null ? unauthorized() : notFound();
        boolean rollback = preferContains(prefer, "tx=rollback");

        RestQueryCompiler.CompiledResult compiled;
        if (body instanceof List<?> list) {
            compiled = ctx.compiler().compileBulkInsert(ctx.tableKey(), (List<Map<String, Object>>) list);
        } else if (body instanceof Map<?, ?> map) {
            var row = (Map<String, Object>) map;
            compiled = preferContains(prefer, "resolution=merge-duplicates")
                ? ctx.compiler().compileUpsert(ctx.tableKey(), row, ctx.schemaInfo().getPrimaryKeys(ctx.tableKey()))
                : ctx.compiler().compileInsert(ctx.tableKey(), row);
        } else {
            return ResponseEntity.badRequest().body(Map.of("error", "Body must be JSON object or array"));
        }

        return executeDml(compiled, ctx.claims(), rollback, null, prefer, HttpStatus.CREATED);
    }

    @PostMapping(path = "/rpc/{function}")
    public ResponseEntity<Object> rpc(
            @PathVariable String function,
            @RequestBody(required = false) Map<String, Object> params,
            @RequestHeader(value = "Content-Profile", required = false) String cp,
            HttpServletRequest request) {

        String schema = resolveSchema(cp);
        if (schema == null) return notFound();
        var claims = getClaims(request);
        if (jwtEnabled && claims == null) return unauthorized();
        var schemaInfo = schemaProvider.resolveSchemaInfo(claims);
        if (!schemaInfo.getStoredProcedures().containsKey(schema + DOT + function)) return notFound();

        try {
            var d = schemaProvider.resolveDialect(claims);
            var ps = new MapSqlParameterSource();
            StringBuilder args = new StringBuilder();
            if (params != null) {
                int i = 0;
                for (var entry : params.entrySet()) {
                    if (i > 0) args.append(COMMA_SEP);
                    String pn = "rpc_" + (i++);
                    args.append(PARAM_PREFIX).append(pn);
                    ps.addValue(pn, entry.getValue());
                }
            }
            String sql = SELECT + d.quoteIdentifier(schema) + DOT + d.quoteIdentifier(function) + parens(args.toString());
            var result = namedJdbc.queryForMap(sql, ps);
            Object value = result.values().iterator().next();
            return ResponseEntity.ok(Map.of("result", value != null ? value : "null"));
        } catch (Exception e) {
            log.warn("rest_rpc_failed function={}", function, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "RPC execution failed"));
        }
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

        var ctx = resolveContext(table, cp, request);
        if (ctx == null) return jwtEnabled && getClaims(request) == null ? unauthorized() : notFound();
        var filters = parseFilters(allParams);
        if (filters.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "At least one filter is required"));

        boolean rollback = preferContains(prefer, "tx=rollback");
        Integer maxAffected = parseMaxAffected(prefer);
        return executeDml(ctx.compiler().compileDelete(ctx.tableKey(), filters), ctx.claims(), rollback, maxAffected, prefer, HttpStatus.OK);
    }


    private ResponseEntity<Object> mutate(String table, Map<String, Object> body, String prefer, String cp, Map<String, String> allParams, HttpServletRequest request) {
        var ctx = resolveContext(table, cp, request);
        if (ctx == null) return jwtEnabled && getClaims(request) == null ? unauthorized() : notFound();
        var filters = parseFilters(allParams);
        if (filters.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "At least one filter is required"));

        boolean rollback = preferContains(prefer, "tx=rollback");
        Integer maxAffected = parseMaxAffected(prefer);
        return executeDml(ctx.compiler().compileUpdate(ctx.tableKey(), body, filters), ctx.claims(), rollback, maxAffected, prefer, HttpStatus.OK);
    }


    @FunctionalInterface
    private interface QueryResultHandler {
        ResponseEntity<Object> handle(Map<String, Object> rows) throws Exception;
    }

    private ResponseEntity<Object> executeInTx(RestQueryCompiler.CompiledResult compiled, JwtClaims claims, QueryResultHandler handler) {
        return txTemplate.execute(status -> {
            try {
                setRlsContext(claims);
                var rows = namedJdbc.queryForMap(compiled.sql(), new MapSqlParameterSource(compiled.params()));
                return handler.handle(rows);
            } catch (Exception e) {
                log.warn("rest_query_failed", e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Query execution failed"));
            }
        });
    }

    private ResponseEntity<Object> executeDml(RestQueryCompiler.CompiledResult compiled, JwtClaims claims, boolean rollback, Integer maxAffected, String prefer, HttpStatus successStatus) {
        return txTemplate.execute(status -> {
            try {
                setRlsContext(claims);
                String json = namedJdbc.queryForObject(compiled.sql(), new MapSqlParameterSource(compiled.params()), String.class);

                if (maxAffected != null && json != null) {
                    List<?> rows = parseJsonList((Object) json);
                    if (rows.size() > maxAffected) {
                        status.setRollbackOnly();
                        return ResponseEntity.badRequest().body(Map.of("error", "Affected rows exceed max-affected limit"));
                    }
                }
                if (rollback) status.setRollbackOnly();

                List<String> applied = new ArrayList<>();
                if (rollback) applied.add("tx=rollback");
                if (preferContains(prefer, "return=representation") && json != null) {
                    applied.add("return=representation");
                    ResponseEntity<Object> resp = ResponseEntity.status(successStatus).body(Map.of("data", parseJson(json)));
                    return applied.isEmpty() ? resp : withHeader(resp, "Preference-Applied", String.join(", ", applied));
                }
                ResponseEntity<Object> resp = ResponseEntity.status(successStatus).body(null);
                return applied.isEmpty() ? resp : withHeader(resp, "Preference-Applied", String.join(", ", applied));
            } catch (Exception e) {
                log.warn("rest_dml_failed", e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Mutation execution failed"));
            }
        });
    }

    private void setRlsContext(JwtClaims claims) {
        if (claims == null) return;
        namedJdbc.update(RLS_SET_CONFIG, Map.of("key", RLS_USER_ID, "val", String.valueOf(claims.userId())));
        if (claims.projectId() != null) {
            namedJdbc.update(RLS_SET_CONFIG, Map.of("key", RLS_PROJECT_ID, "val", claims.projectId()));
        }
    }


    private ResponseEntity<Object> buildCsvResponse(Map<String, Object> rows, List<String> cols) {
        List<?> data = parseJsonList(rows.get("body"));
        if (data.isEmpty()) return ResponseEntity.ok().contentType(CSV_TYPE).body((Object) "");
        StringBuilder csv = new StringBuilder();
        csv.append(String.join(",", cols)).append("\n");
        for (Object row : data) {
            if (row instanceof Map<?, ?> raw) {
                @SuppressWarnings("unchecked") var m = (Map<String, Object>) raw;
                List<String> vals = new ArrayList<>();
                for (String col : cols) vals.add(csvEscape(String.valueOf(m.getOrDefault(col, ""))));
                csv.append(String.join(",", vals)).append("\n");
            }
        }
        return ResponseEntity.ok().contentType(CSV_TYPE).body((Object) csv.toString());
    }

    private static String csvEscape(String value) {
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }


    private record RequestContext(String tableKey, RestQueryCompiler compiler, SchemaInfo schemaInfo, JwtClaims claims) {}

    private record ParsedParams(List<String> columns, List<RestQueryCompiler.FilterSpec> filters,
                                List<RestQueryCompiler.OrCondition> orConditions, List<RestQueryCompiler.EmbedSpec> embeds,
                                List<RestQueryCompiler.OrderBySpec> orderSpecs) {}

    private ParsedParams parseSelectParams(String select, String order, Map<String, String> allParams) {
        var selectResult = SelectParser.parse(select);
        return new ParsedParams(
            selectResult.columns(),
            parseFilters(allParams),
            parseOrConditions(allParams),
            selectResult.embeds().stream().map(e -> new RestQueryCompiler.EmbedSpec(e.relationName(), e.columns())).toList(),
            OrderParser.parse(order).stream().map(o -> new RestQueryCompiler.OrderBySpec(o.column(), o.direction(), o.nulls())).toList());
    }

    private RequestContext resolveContext(String table, String profileHeader, HttpServletRequest request) {
        String schema = resolveSchema(profileHeader);
        if (schema == null) return null;
        var claims = getClaims(request);
        if (jwtEnabled && claims == null) return null;
        String tableKey = schema + DOT + table;
        var schemaInfo = schemaProvider.resolveSchemaInfo(claims);
        if (!schemaInfo.hasTable(tableKey)) return null;
        return new RequestContext(tableKey, new RestQueryCompiler(schemaInfo, schemaProvider.resolveDialect(claims), schema, maxRows), schemaInfo, claims);
    }

    private String resolveSchema(String header) {
        if (header == null || header.isBlank()) return defaultSchema;
        String s = header.trim();
        return allowedSchemas.contains(s) ? s : null;
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
                catch (NumberFormatException e) { return null; }
            }
        }
        return null;
    }

    private static ResponseEntity<Object> withHeader(ResponseEntity<Object> resp, String name, String value) {
        return ResponseEntity.status(resp.getStatusCode()).headers(resp.getHeaders()).header(name, value).body(resp.getBody());
    }

    private static ResponseEntity<Object> notFound() { return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Not found")); }
    private static ResponseEntity<Object> unauthorized() { return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required")); }

    private JwtClaims getClaims(HttpServletRequest request) { return (JwtClaims) request.getAttribute(SecurityConstants.JWT_CLAIMS_ATTR); }

    private List<RestQueryCompiler.FilterSpec> parseFilters(Map<String, String> allParams) {
        List<RestQueryCompiler.FilterSpec> filters = new ArrayList<>();
        for (var entry : allParams.entrySet()) {
            if (RESERVED_PARAMS.contains(entry.getKey())) continue;
            var p = FilterParser.parse(entry.getKey(), entry.getValue());
            filters.add(new RestQueryCompiler.FilterSpec(p.column(), p.operator(), p.value(), p.negated()));
        }
        return filters;
    }

    private List<RestQueryCompiler.OrCondition> parseOrConditions(Map<String, String> allParams) {
        String or = allParams.get("or");
        if (or == null || or.isBlank()) return List.of();
        return List.of(new RestQueryCompiler.OrCondition(
            FilterParser.parseOr(or).stream().map(p -> new RestQueryCompiler.FilterSpec(p.column(), p.operator(), p.value(), p.negated())).toList()));
    }

    private Object parseJson(String json) {
        try { return mapper.readValue(json, Object.class); } catch (Exception e) { return json; }
    }

    @SuppressWarnings("unchecked")
    private List<?> parseJsonList(Object body) {
        if (body == null) return List.of();
        try { return mapper.readValue(body.toString(), List.class); } catch (Exception e) { return List.of(); }
    }
}
