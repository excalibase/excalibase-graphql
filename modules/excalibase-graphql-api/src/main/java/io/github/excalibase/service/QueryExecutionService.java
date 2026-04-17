package io.github.excalibase.service;

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
import java.util.HashMap;
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
                                        MapSqlParameterSource params) throws Exception {
        String json = namedJdbc.queryForObject(compiled.sql(), params, String.class);
        return wrapResult(json, compiled);
    }

    public ResponseEntity<Object> executeTwoPhase(SqlCompiler.CompiledQuery compiled,
                                           MapSqlParameterSource params,
                                           MutationExecutor mutationExecutor) throws Exception {
        String json = mutationExecutor.execute(compiled, params, namedJdbc);
        if (json != null) {
            Object result = objectMapper.readValue(json, Object.class);
            return ResponseEntity.ok(Map.of("data", result));
        }
        return ResponseEntity.ok(Map.of("data", Map.of()));
    }

    @SuppressWarnings("java:S3776") // JDBC CallableStatement handling: IN/OUT/INOUT param registration, OUT value extraction, result set conversion must happen in sequence
    public ResponseEntity<Object> executeProcedureCall(SqlCompiler.CompiledQuery compiled) throws Exception {
        SqlCompiler.ProcedureCallInfo callInfo = compiled.procedureCallInfo();
        String fieldName = compiled.mutationFieldName();

        // qualifiedName comes from schema introspection — validate it only contains safe identifier chars
        String qualifiedName = callInfo.qualifiedName();
        if (!SAFE_IDENTIFIER.matcher(qualifiedName).matches()) {
            throw new IllegalArgumentException("Invalid procedure name: " + qualifiedName);
        }

        int paramCount = callInfo.allParams().size();
        String placeholders = paramCount == 0 ? "" : "?,".repeat(paramCount).substring(0, paramCount * 2 - 1);
        String callSql = "CALL " + qualifiedName + "(" + placeholders + ")";

        try (Connection conn = dataSource.getConnection();
             CallableStatement cs = conn.prepareCall(callSql)) { // NOSONAR — qualifiedName validated above, args use setObject placeholders

            int idx = 1;
            for (SqlCompiler.ProcedureCallParam p : callInfo.allParams()) {
                if ("IN".equals(p.mode()) || PROC_PARAM_MODE_INOUT.equals(p.mode())) {
                    if (p.value() != null) {
                        cs.setObject(idx, p.value(), SqlUtils.sqlTypeFor(p.type()));
                    } else {
                        cs.setNull(idx, SqlUtils.sqlTypeFor(p.type()));
                    }
                }
                if ("OUT".equals(p.mode()) || PROC_PARAM_MODE_INOUT.equals(p.mode())) {
                    cs.registerOutParameter(idx, SqlUtils.sqlTypeFor(p.type()));
                }
                idx++;
            }
            cs.execute();

            Map<String, Object> result = new HashMap<>();
            idx = 1;
            for (SqlCompiler.ProcedureCallParam p : callInfo.allParams()) {
                if ("OUT".equals(p.mode()) || PROC_PARAM_MODE_INOUT.equals(p.mode())) {
                    result.put(p.name(), cs.getObject(idx));
                }
                idx++;
            }

            if (result.isEmpty()) {
                return ResponseEntity.ok(Map.of("data", Map.of(fieldName, "{\"result\":\"OK\"}")));
            }
            String json = objectMapper.writeValueAsString(result);
            return ResponseEntity.ok(Map.of("data", Map.of(fieldName, json)));
        }
    }

    public ResponseEntity<Object> executeWithRlsContext(SqlCompiler.CompiledQuery compiled,
                                                       String userId,
                                                       JwtClaims jwtClaims) throws Exception {
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

            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        }
    }

    private ResponseEntity<Object> wrapResult(String json,
                                              SqlCompiler.CompiledQuery compiled) throws Exception {
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
