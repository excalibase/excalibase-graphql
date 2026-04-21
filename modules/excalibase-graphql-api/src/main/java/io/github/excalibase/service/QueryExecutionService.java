package io.github.excalibase.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.compiler.SqlCompiler;
import io.github.excalibase.utils.SqlUtils;
import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.spi.MutationExecutor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Executes compiled SQL queries against the database.
 * Handles normal queries, RLS-context queries, stored procedures, and two-phase mutations.
 */
@Component
public class QueryExecutionService {

    /** Only allows quoted identifiers like "schema"."proc_name" or schema.`proc_name` */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[a-zA-Z0-9_.\"`]+$");
    private static final String PROC_PARAM_MODE_INOUT = "INOUT";

    private final NamedParameterJdbcTemplate namedJdbc;
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public QueryExecutionService(NamedParameterJdbcTemplate namedJdbc,
                                 DataSource dataSource,
                                 ObjectMapper objectMapper) {
        this.namedJdbc = namedJdbc;
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    public ResponseEntity<Object> executeQuery(SqlCompiler.CompiledQuery compiled,
                                        MapSqlParameterSource params) throws JsonProcessingException {
        String json = namedJdbc.queryForObject(compiled.sql(), params, String.class);
        return wrapResult(json, compiled);
    }

    public ResponseEntity<Object> executeTwoPhase(SqlCompiler.CompiledQuery compiled,
                                           MapSqlParameterSource params,
                                           MutationExecutor mutationExecutor) throws JsonProcessingException {
        String json = mutationExecutor.execute(compiled, params, namedJdbc);
        if (json != null) {
            Object result = objectMapper.readValue(json, Object.class);
            return ResponseEntity.ok(Map.of("data", result));
        }
        return ResponseEntity.ok(Map.of("data", Map.of()));
    }

    public ResponseEntity<Object> executeProcedureCall(SqlCompiler.CompiledQuery compiled) throws SQLException, JsonProcessingException {
        SqlCompiler.ProcedureCallInfo callInfo = compiled.procedureCallInfo();
        String fieldName = compiled.mutationFieldName();
        String callSql = buildCallSql(callInfo);
        try (Connection conn = dataSource.getConnection();
             CallableStatement cs = conn.prepareCall(callSql)) { // NOSONAR — qualifiedName validated in buildCallSql, args use setObject placeholders
            var params = callInfo.allParams();
            bindInParams(cs, params);
            registerOutParams(cs, params);
            cs.execute();
            Map<String, Object> result = collectOutParams(cs, params);
            return wrapProcedureResult(fieldName, result);
        }
    }

    private String buildCallSql(SqlCompiler.ProcedureCallInfo callInfo) {
        // qualifiedName comes from schema introspection — validate it only contains safe identifier chars
        String qualifiedName = callInfo.qualifiedName();
        if (!SAFE_IDENTIFIER.matcher(qualifiedName).matches()) {
            throw new IllegalArgumentException("Invalid procedure name: " + qualifiedName);
        }
        int paramCount = callInfo.allParams().size();
        String placeholders = paramCount == 0 ? "" : "?,".repeat(paramCount).substring(0, paramCount * 2 - 1);
        return "CALL " + qualifiedName + "(" + placeholders + ")";
    }

    private void bindInParams(CallableStatement cs, List<SqlCompiler.ProcedureCallParam> params) throws SQLException {
        int idx = 1;
        for (SqlCompiler.ProcedureCallParam param : params) {
            if (isInput(param)) {
                if (param.value() != null) {
                    cs.setObject(idx, param.value(), SqlUtils.sqlTypeFor(param.type()));
                } else {
                    cs.setNull(idx, SqlUtils.sqlTypeFor(param.type()));
                }
            }
            idx++;
        }
    }

    private void registerOutParams(CallableStatement cs, List<SqlCompiler.ProcedureCallParam> params) throws SQLException {
        int idx = 1;
        for (SqlCompiler.ProcedureCallParam param : params) {
            if (isOutput(param)) {
                cs.registerOutParameter(idx, SqlUtils.sqlTypeFor(param.type()));
            }
            idx++;
        }
    }

    private Map<String, Object> collectOutParams(CallableStatement cs, List<SqlCompiler.ProcedureCallParam> params) throws SQLException {
        Map<String, Object> result = new HashMap<>();
        int idx = 1;
        for (SqlCompiler.ProcedureCallParam param : params) {
            if (isOutput(param)) {
                result.put(param.name(), cs.getObject(idx));
            }
            idx++;
        }
        return result;
    }

    private ResponseEntity<Object> wrapProcedureResult(String fieldName, Map<String, Object> result) throws JsonProcessingException {
        if (result.isEmpty()) {
            return ResponseEntity.ok(Map.of("data", Map.of(fieldName, "{\"result\":\"OK\"}")));
        }
        String json = objectMapper.writeValueAsString(result);
        return ResponseEntity.ok(Map.of("data", Map.of(fieldName, json)));
    }

    private static boolean isInput(SqlCompiler.ProcedureCallParam param) {
        return "IN".equals(param.mode()) || PROC_PARAM_MODE_INOUT.equals(param.mode());
    }

    private static boolean isOutput(SqlCompiler.ProcedureCallParam param) {
        return "OUT".equals(param.mode()) || PROC_PARAM_MODE_INOUT.equals(param.mode());
    }

    public ResponseEntity<Object> executeWithRlsContext(SqlCompiler.CompiledQuery compiled,
                                                       String userId,
                                                       JwtClaims jwtClaims) throws SQLException, JsonProcessingException {
        try (Connection conn = dataSource.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);

                try (var pstmt = conn.prepareStatement("SELECT set_config('request.user_id', ?, true)")) {
                    pstmt.setString(1, userId);
                    pstmt.execute();
                }
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

                SqlUtils.ResolvedSql resolvedSql = SqlUtils.resolveNamedParams(compiled.sql(), compiled.params());

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
                return wrapResult(json, compiled);

            } catch (SQLException | JsonProcessingException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        }
    }

    private ResponseEntity<Object> wrapResult(String json,
                                              SqlCompiler.CompiledQuery compiled) throws JsonProcessingException {
        if (json != null) {
            if (compiled.isProcedureCall()) {
                return ResponseEntity.ok(Map.of("data", Map.of(compiled.mutationFieldName(), json)));
            }
            Object result = objectMapper.readValue(json, Object.class);
            return ResponseEntity.ok(Map.of("data", result));
        }
        return ResponseEntity.ok(Map.of("data", Map.of()));
    }
}
