package io.github.excalibase.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.*;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Types;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single endpoint: parse GraphQL -> compile to SQL -> execute -> return JSON.
 * Introspection queries (__schema, __type) passthrough to GraphQL-Java.
 * Supports both PostgreSQL and MySQL via app.database-type config.
 */
@RestController
public class GraphqlController {

    private static final Logger log = LoggerFactory.getLogger(GraphqlController.class);

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;
    private final String dbSchema;
    private final int maxRows;
    private final String databaseType;
    private final int maxQueryDepth;

    private record EngineState(SqlCompiler compiler, IntrospectionHandler introspectionHandler,
                               MutationExecutor mutationExecutor) {}
    private volatile EngineState engineState;

    public GraphqlController(
            JdbcTemplate jdbcTemplate,
            NamedParameterJdbcTemplate namedJdbc,
            DataSource dataSource,
            ObjectMapper objectMapper,
            TransactionTemplate txTemplate,
            @Value("${app.schemas:public}") String dbSchema,
            @Value("${app.max-rows:30}") int maxRows,
            @Value("${app.database-type:postgres}") String databaseType,
            @Value("${app.max-query-depth:0}") int maxQueryDepth,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            NatsCDCService natsCDCService) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbc = namedJdbc;
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.txTemplate = txTemplate;
        this.dbSchema = dbSchema;
        this.maxRows = maxRows;
        this.databaseType = databaseType;
        this.maxQueryDepth = maxQueryDepth;
        if (natsCDCService != null) {
            natsCDCService.setSchemaReloadCallback(this::reload);
        }
    }

    @PostConstruct
    public void init() {
        SqlEngine engine = SqlEngineFactory.create(databaseType);
        List<String> schemaList = resolveSchemaList();

        SchemaInfo schemaInfo = new SchemaInfo();
        try {
            loadMultiSchema(schemaInfo, schemaList, engine.schemaLoader());
        } catch (Exception e) {
            log.warn("Failed to load database schema — starting with empty schema", e);
        }

        String defaultSchema = schemaList.isEmpty() ? "public" : schemaList.getFirst();
        SqlCompiler newCompiler = new SqlCompiler(schemaInfo, defaultSchema, maxRows, engine.dialect(), engine.mutationCompiler(), maxQueryDepth);
        IntrospectionHandler newHandler = null;
        try {
            newHandler = new IntrospectionHandler(schemaInfo);
        } catch (Exception e) {
            log.warn("IntrospectionHandler failed to build schema", e);
        }
        MutationExecutor mutationExecutor = SqlEngineFactory.createMutationExecutor(databaseType, jdbcTemplate, txTemplate);
        // Atomic swap — concurrent requests see either old or new state, never mixed
        engineState = new EngineState(newCompiler, newHandler, mutationExecutor);
    }

    private List<String> resolveSchemaList() {
        if ("ALL".equalsIgnoreCase(dbSchema.trim())) {
            return discoverSchemas();
        }
        return java.util.Arrays.stream(dbSchema.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private List<String> discoverSchemas() {
        try {
            return jdbcTemplate.queryForList(
                    "SELECT schema_name FROM information_schema.schemata " +
                    "WHERE schema_name NOT IN ('pg_catalog', 'information_schema') " +
                    "AND schema_name NOT LIKE 'pg_toast%' " +
                    "AND schema_name NOT LIKE 'pg_temp_%' " +
                    "ORDER BY schema_name",
                    String.class);
        } catch (Exception e) {
            log.warn("Failed to discover schemas", e);
            return List.of();
        }
    }

    private void loadMultiSchema(SchemaInfo schemaInfo, List<String> schemaList, SchemaLoader loader) {
        // Single bulk load — 1 query for Postgres, fallback to 8×N for others
        Map<String, SchemaInfo> perSchema = new LinkedHashMap<>();
        loader.loadAll(jdbcTemplate, schemaList, perSchema);

        // Merge per-schema data into compound-key SchemaInfo
        for (var schemaEntry : perSchema.entrySet()) {
            String schema = schemaEntry.getKey();
            SchemaInfo temp = schemaEntry.getValue();

            for (String table : temp.getTableNames()) {
                String key = schema + "." + table;
                for (String col : temp.getColumns(table)) {
                    schemaInfo.addColumn(key, col, temp.getColumnType(table, col));
                    String enumType = temp.getEnumType(table, col);
                    if (enumType != null) {
                        schemaInfo.addColumnEnumType(key, col, schema + "." + enumType);
                    }
                }
                schemaInfo.setTableSchema(key, schema);
                for (String pk : temp.getPrimaryKeys(table)) {
                    schemaInfo.addPrimaryKey(key, pk);
                }
                if (temp.isView(table)) {
                    schemaInfo.addView(key);
                }
                var computed = temp.getComputedFields(table);
                if (computed != null) {
                    for (var cf : computed) {
                        schemaInfo.addComputedField(key, cf.functionName(), cf.returnType());
                    }
                }
            }
            for (var entry : temp.getEnumTypes().entrySet()) {
                for (String label : entry.getValue()) {
                    schemaInfo.addEnumValue(schema + "." + entry.getKey(), label);
                }
            }
            for (var entry : temp.getStoredProcedures().entrySet()) {
                schemaInfo.addStoredProcedure(schema + "." + entry.getKey(), entry.getValue());
            }
            for (var entry : temp.getCompositeTypes().entrySet()) {
                for (var field : entry.getValue()) {
                    schemaInfo.addCompositeTypeField(schema + "." + entry.getKey(), field.name(), field.type());
                }
            }
        }

        // FK pass — translate raw table refs to compound keys
        for (var schemaEntry : perSchema.entrySet()) {
            String schema = schemaEntry.getKey();
            SchemaInfo temp = schemaEntry.getValue();
            for (var entry : temp.getAllForwardFks().entrySet()) {
                String fkKey = entry.getKey();
                String fromTable = fkKey.substring(0, fkKey.lastIndexOf('.'));
                SchemaInfo.FkInfo fk = entry.getValue();
                String compoundFrom = schema + "." + fromTable;
                String compoundTo = findCompoundKey(schemaInfo, fk.refTable(), schemaList);
                if (compoundTo != null && schemaInfo.hasTable(compoundFrom)) {
                    if (fk.isComposite()) {
                        schemaInfo.addCompositeForeignKey(compoundFrom, fk.fkColumns(), compoundTo, fk.refColumns());
                    } else {
                        schemaInfo.addForeignKey(compoundFrom, fk.fkColumn(), compoundTo, fk.refColumn());
                    }
                }
            }
        }
    }

    private String findCompoundKey(SchemaInfo schemaInfo, String rawTable, List<String> allSchemas) {
        for (String s : allSchemas) {
            String candidate = s + "." + rawTable;
            if (schemaInfo.hasTable(candidate)) return candidate;
        }
        return null;
    }

    /**
     * Reinitialize schema and compiler. Called on DDL events from NatsCDCService.
     */
    public void reload() {
        init();
        log.info("Schema reloaded");
    }

    @PostMapping("/graphql")
    public ResponseEntity<Object> graphql(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            jakarta.servlet.http.HttpServletRequest httpRequest) {
        // Prefer JWT claims over X-User-Id header
        var jwtClaims = (io.github.excalibase.security.JwtClaims) httpRequest.getAttribute(
                io.github.excalibase.security.JwtAuthFilter.JWT_CLAIMS_ATTR);
        if (jwtClaims != null) {
            userId = String.valueOf(jwtClaims.userId());
        }
        if (!(request.get("query") instanceof String query) || query.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "errors", java.util.List.of(Map.of("message", "Missing or invalid 'query' field"))));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> variables = request.containsKey("variables") && request.get("variables") instanceof Map
                ? (Map<String, Object>) request.get("variables") : Map.of();

        try {
            // Snapshot engine state for this request (atomic read)
            EngineState state = this.engineState;

            // Introspection passthrough to GraphQL-Java
            if (state.compiler().isIntrospection(query)) {
                if (state.introspectionHandler() != null) {
                    return ResponseEntity.ok(state.introspectionHandler().execute(query, variables));
                }
                return ResponseEntity.ok(Map.of("data", Map.of("__schema", Map.of("queryType", Map.of("name", "Query")))));
            }

            SqlCompiler.CompiledQuery compiled = state.compiler().compile(query, variables);
            MapSqlParameterSource params = new MapSqlParameterSource(compiled.params());

            // Check if this is a stored procedure call
            boolean isProcedureCall = compiled.isProcedureCall();

            // Handle stored procedure calls via JDBC CallableStatement
            if (isProcedureCall && compiled.procedureCallInfo() != null) {
                return executeProcedureCall(compiled);
            }

            // Two-phase mutations (MySQL) — delegate to MutationExecutor
            if (compiled.isTwoPhase()) {
                String json = state.mutationExecutor().execute(compiled, params, namedJdbc);
                if (json != null) {
                    Object result = objectMapper.readValue(json, Object.class);
                    return ResponseEntity.ok(Map.of("data", result));
                }
                return ResponseEntity.ok(Map.of("data", null));
            }

            // For RLS-enabled queries: set user context in a transaction
            if (userId != null && !userId.isBlank() && "postgres".equalsIgnoreCase(databaseType)) {
                return executeWithRlsContext(compiled, params, userId, jwtClaims, isProcedureCall);
            }

            String json = namedJdbc.queryForObject(compiled.sql(), params, String.class);

            if (json != null) {
                if (isProcedureCall) {
                    // Procedure call returns a JSON string — wrap in mutation field
                    return ResponseEntity.ok(Map.of("data", Map.of(compiled.mutationFieldName(), json)));
                }
                Object result = objectMapper.readValue(json, Object.class);
                return ResponseEntity.ok(Map.of("data", result));
            }
            return ResponseEntity.ok(Map.of("data", null));

        } catch (Exception e) {
            log.warn("GraphQL request failed", e);
            String message = e.getMessage();
            // Extract the database error message (pg_graphql pattern: expose DB errors)
            if (message != null) {
                int sqlError = message.indexOf("ERROR:");
                if (sqlError >= 0) {
                    message = message.substring(sqlError);
                } else if (message.contains("StatementCallback") || message.contains("PreparedStatementCallback")) {
                    int bracket = message.indexOf("; ");
                    if (bracket > 0) message = message.substring(bracket + 2);
                }
            }
            return ResponseEntity.ok(Map.of(
                    "errors", java.util.List.of(Map.of("message", message != null ? message : "Internal error"))
            ));
        }
    }

    private ResponseEntity<Object> executeProcedureCall(SqlCompiler.CompiledQuery compiled) throws Exception {
        SqlCompiler.ProcedureCallInfo callInfo = compiled.procedureCallInfo();
        String fieldName = compiled.mutationFieldName();

        // Build CALL sql: CALL schema."proc_name"(?, ?, ...)
        int paramCount = callInfo.allParams().size();
        String placeholders = paramCount == 0 ? "" : "?,".repeat(paramCount).substring(0, paramCount * 2 - 1);
        String callSql = "CALL " + callInfo.qualifiedName() + "(" + placeholders + ")";

        try (Connection conn = dataSource.getConnection()) {
            try (CallableStatement cs = conn.prepareCall(callSql)) {
                int idx = 1;
                for (SqlCompiler.ProcedureCallParam p : callInfo.allParams()) {
                    if ("IN".equals(p.mode()) || "INOUT".equals(p.mode())) {
                        if (p.value() != null) {
                            cs.setObject(idx, p.value(), sqlTypeFor(p.type()));
                        } else {
                            cs.setNull(idx, sqlTypeFor(p.type()));
                        }
                    }
                    if ("OUT".equals(p.mode()) || "INOUT".equals(p.mode())) {
                        cs.registerOutParameter(idx, sqlTypeFor(p.type()));
                    }
                    idx++;
                }
                cs.execute();

                // Collect OUT params into JSON
                Map<String, Object> result = new HashMap<>();
                idx = 1;
                for (SqlCompiler.ProcedureCallParam p : callInfo.allParams()) {
                    if ("OUT".equals(p.mode()) || "INOUT".equals(p.mode())) {
                        result.put(p.name(), cs.getObject(idx));
                    }
                    idx++;
                }

                if (result.isEmpty()) {
                    String json = "{\"result\":\"OK\"}";
                    return ResponseEntity.ok(Map.of("data", Map.of(fieldName, json)));
                }

                String json = objectMapper.writeValueAsString(result);
                return ResponseEntity.ok(Map.of("data", Map.of(fieldName, json)));
            }
        }
    }

    private int sqlTypeFor(String pgType) {
        if (pgType == null) return Types.OTHER;
        String t = pgType.toLowerCase();
        if (t.contains("int") || t.equals("bigint")) return Types.BIGINT;
        if (t.contains("numeric") || t.contains("decimal")) return Types.NUMERIC;
        if (t.contains("float") || t.contains("double") || t.equals("real")) return Types.DOUBLE;
        if (t.equals("boolean") || t.equals("bool")) return Types.BOOLEAN;
        if (t.equals("text") || t.startsWith("varchar") || t.startsWith("character")) return Types.VARCHAR;
        return Types.OTHER;
    }

    private ResponseEntity<Object> executeWithRlsContext(
            SqlCompiler.CompiledQuery compiled,
            MapSqlParameterSource params,
            String userId,
            io.github.excalibase.security.JwtClaims jwtClaims,
            boolean isProcedureCall) throws Exception {
        // Use raw connection to set session variable and execute query in same connection
        try (Connection conn = dataSource.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);
                // Set RLS context variable (parameterized like PostgREST)
                try (var pstmt = conn.prepareStatement("SELECT set_config('request.user_id', ?, true)")) {
                    pstmt.setString(1, userId);
                    pstmt.execute();
                }
                // Set additional JWT claims as session vars when available
                if (jwtClaims != null) {
                    try (var pstmt = conn.prepareStatement("SELECT set_config('request.project_id', ?, true)")) {
                        pstmt.setString(1, jwtClaims.projectId());
                        pstmt.execute();
                    }
                    try (var pstmt = conn.prepareStatement("SELECT set_config('request.role', ?, true)")) {
                        pstmt.setString(1, jwtClaims.role());
                        pstmt.execute();
                    }
                }

                // Execute the query using the same connection
                String sql = compiled.sql();
                // Replace named parameters with positional parameters
                var resolvedSql = resolveNamedParams(sql, compiled.params());

                String json;
                try (var pstmt = conn.prepareStatement(resolvedSql.sql())) {
                    for (int i = 0; i < resolvedSql.values().size(); i++) {
                        pstmt.setObject(i + 1, resolvedSql.values().get(i));
                    }
                    try (var rs = pstmt.executeQuery()) {
                        json = rs.next() ? rs.getString(1) : null;
                    }
                }

                conn.commit();

                if (json != null) {
                    if (isProcedureCall) {
                        return ResponseEntity.ok(Map.of("data", Map.of(compiled.mutationFieldName(), json)));
                    }
                    Object result = objectMapper.readValue(json, Object.class);
                    return ResponseEntity.ok(Map.of("data", result));
                }
                return ResponseEntity.ok(Map.of("data", null));

            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        }
    }

    private record ResolvedSql(String sql, java.util.List<Object> values) {}

    private ResolvedSql resolveNamedParams(String sql, Map<String, Object> params) {
        java.util.List<Object> values = new java.util.ArrayList<>();
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < sql.length()) {
            if (sql.charAt(i) == ':' && i + 1 < sql.length() && Character.isLetter(sql.charAt(i + 1))) {
                int start = i + 1;
                int end = start;
                while (end < sql.length() && (Character.isLetterOrDigit(sql.charAt(end)) || sql.charAt(end) == '_')) {
                    end++;
                }
                String paramName = sql.substring(start, end);
                if (params.containsKey(paramName)) {
                    values.add(params.get(paramName));
                    result.append('?');
                    // Append any cast suffix after the param name (e.g., ::date)
                    i = end;
                    if (i < sql.length() && sql.charAt(i) == ':' && i + 1 < sql.length() && sql.charAt(i + 1) == ':') {
                        // Append the cast suffix as-is
                        int castEnd = i + 2;
                        while (castEnd < sql.length() && (Character.isLetterOrDigit(sql.charAt(castEnd))
                                || sql.charAt(castEnd) == '_' || sql.charAt(castEnd) == '.'
                                || sql.charAt(castEnd) == '"' || sql.charAt(castEnd) == ' ')) {
                            // Stop at common SQL delimiters
                            if (sql.charAt(castEnd) == ' ' || sql.charAt(castEnd) == ',' || sql.charAt(castEnd) == ')'
                                    || sql.charAt(castEnd) == '\n') break;
                            castEnd++;
                        }
                        result.append(sql, i, castEnd);
                        i = castEnd;
                    }
                } else {
                    result.append(sql.charAt(i));
                    i++;
                }
            } else if (sql.charAt(i) == '\'' ) {
                // Skip string literals
                result.append(sql.charAt(i));
                i++;
                while (i < sql.length() && sql.charAt(i) != '\'') {
                    result.append(sql.charAt(i));
                    i++;
                }
                if (i < sql.length()) {
                    result.append(sql.charAt(i));
                    i++;
                }
            } else {
                result.append(sql.charAt(i));
                i++;
            }
        }
        return new ResolvedSql(result.toString(), values);
    }
}
