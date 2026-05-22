package io.github.excalibase.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.compiler.SqlCompiler;
import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.security.PostgresRoleResolver;
import io.github.excalibase.security.RoleContext;
import io.github.excalibase.spi.MutationExecutor;
import io.github.excalibase.utils.SqlUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Executes compiled SQL against the database. Two paths:
 *
 * <ul>
 *   <li>{@link #executeInContext} — Postgres path. Single connection + transaction.
 *       Sets {@code request.user_id/project_id/role} session vars and (when role
 *       switching is enabled) issues {@code SET LOCAL ROLE} before dispatching to
 *       the right query / two-phase mutation / procedure call branch.</li>
 *   <li>Legacy {@link #executeQuery} / {@link #executeTwoPhase} / {@link #executeProcedureCall} —
 *       used for MySQL / Mongo or single-tenant Postgres deploys with no JWT and
 *       role switching disabled. No transaction, no RLS context.</li>
 * </ul>
 *
 * <p>The legacy {@link #executeWithRlsContext} entry point is preserved as a
 * thin delegation to {@link #executeInContext} for backward compatibility.
 */
@Component
public class QueryExecutionService {

    /** Only allows quoted identifiers like "schema"."proc_name" or schema.`proc_name` */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[a-zA-Z0-9_.\"`]+$");
    private static final String PROC_PARAM_MODE_INOUT = "INOUT";

    private final NamedParameterJdbcTemplate namedJdbc;
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final PostgresRoleResolver roleResolver;

    public QueryExecutionService(NamedParameterJdbcTemplate namedJdbc,
                                 DataSource dataSource,
                                 ObjectMapper objectMapper,
                                 PostgresRoleResolver roleResolver) {
        this.namedJdbc = namedJdbc;
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.roleResolver = roleResolver;
    }

    /** Whether Postgres role switching is enabled (driven by config). */
    public boolean isRoleSwitchingEnabled() {
        return roleResolver != null && roleResolver.isEnabled();
    }

    // ------------------------------------------------------------------------
    // Legacy paths — used only when feature is off AND no JWT (MySQL / Mongo).
    // ------------------------------------------------------------------------

    public ResponseEntity<Object> executeQuery(SqlCompiler.CompiledQuery compiled,
                                               MapSqlParameterSource params) throws JsonProcessingException {
        String json = namedJdbc.queryForObject(compiled.sql(), params, String.class);
        return wrapResult(json, compiled);
    }

    public ResponseEntity<Object> executeTwoPhase(SqlCompiler.CompiledQuery compiled,
                                                  MapSqlParameterSource params,
                                                  MutationExecutor mutationExecutor) throws JsonProcessingException {
        String json = mutationExecutor.execute(compiled, params, namedJdbc);
        return wrapTwoPhaseResult(json);
    }

    public ResponseEntity<Object> executeProcedureCall(SqlCompiler.CompiledQuery compiled) throws SQLException, JsonProcessingException {
        try (Connection conn = dataSource.getConnection()) {
            return executeProcedureCallOn(conn, compiled);
        }
    }

    // ------------------------------------------------------------------------
    // Backward-compat: pre-existing callers used (compiled, userId, claims).
    // The userId always equalled claims.userId() in practice — we drop it.
    // ------------------------------------------------------------------------

    public ResponseEntity<Object> executeWithRlsContext(SqlCompiler.CompiledQuery compiled,
                                                        String userId,
                                                        JwtClaims jwtClaims) throws SQLException, JsonProcessingException {
        JwtClaims effective = jwtClaims;
        if (effective == null && userId != null && !userId.isBlank()) {
            // Legacy contract: when caller passes only userId (no claims), set just
            // request.user_id and skip request.project_id / request.role.
            effective = new JwtClaims(userId, null, null, null, null, null, null, null, 0L);
        }
        return executeInContext(compiled, null, null, effective);
    }

    // ------------------------------------------------------------------------
    // New consolidated path — Postgres role-switching aware.
    // ------------------------------------------------------------------------

    /**
     * Executes a compiled query inside one connection + manual transaction.
     *
     * <ol>
     *   <li>Sets {@code request.user_id/project_id/role} session vars from the
     *       claims (legacy RLS contract).</li>
     *   <li>If role switching is enabled, resolves the JWT to a Postgres role and
     *       issues {@code SET LOCAL ROLE "<role>"}. The role string is post-validated
     *       by {@link PostgresRoleResolver}; {@code SET ROLE} cannot use parameter
     *       placeholders so app-side allowlisting is mandatory.</li>
     *   <li>Dispatches to procedure / two-phase / plain branches sharing the same
     *       connection — closes the pre-existing gap where these branches bypassed
     *       RLS context entirely.</li>
     * </ol>
     */
    public ResponseEntity<Object> executeInContext(SqlCompiler.CompiledQuery compiled,
                                                   MapSqlParameterSource params,
                                                   MutationExecutor mutationExecutor,
                                                   JwtClaims claims) throws SQLException, JsonProcessingException {
        // RoleContext is populated by JwtAuthFilter (single source of truth across
        // GraphQL + REST surfaces). When the filter didn't run (mocked unit tests,
        // direct calls), fall back to running the resolver here so behaviour is
        // identical regardless of entry point.
        String pgRole = RoleContext.getRole();
        if (pgRole == null && roleResolver != null) {
            pgRole = roleResolver.resolve(claims);
        }

        try (Connection conn = dataSource.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);

                applyRequestContext(conn, claims);
                if (pgRole != null) {
                    applySetLocalRole(conn, pgRole);
                }

                ResponseEntity<Object> result = dispatchInsideTx(conn, compiled, params, mutationExecutor);
                conn.commit();
                return result;

            } catch (SQLException | JsonProcessingException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        }
    }

    private ResponseEntity<Object> dispatchInsideTx(Connection conn,
                                                    SqlCompiler.CompiledQuery compiled,
                                                    MapSqlParameterSource params,
                                                    MutationExecutor mutationExecutor) throws SQLException, JsonProcessingException {
        if (compiled.isProcedureCall() && compiled.procedureCallInfo() != null) {
            return executeProcedureCallOn(conn, compiled);
        }
        if (compiled.isTwoPhase() && mutationExecutor != null) {
            return executeTwoPhaseOn(conn, compiled, params, mutationExecutor);
        }
        return executePlainOn(conn, compiled);
    }

    // ------------------------------------------------------------------------
    // Per-branch implementations against a shared Connection.
    // ------------------------------------------------------------------------

    private ResponseEntity<Object> executePlainOn(Connection conn,
                                                  SqlCompiler.CompiledQuery compiled) throws SQLException, JsonProcessingException {
        SqlUtils.ResolvedSql resolvedSql = SqlUtils.resolveNamedParams(compiled.sql(), compiled.params());
        String json;
        try (PreparedStatement pstmt = conn.prepareStatement(resolvedSql.sql())) {
            for (int i = 0; i < resolvedSql.values().size(); i++) {
                pstmt.setObject(i + 1, resolvedSql.values().get(i));
            }
            try (var rs = pstmt.executeQuery()) {
                json = rs.next() ? rs.getString(1) : null;
            }
        }
        return wrapResult(json, compiled);
    }

    private ResponseEntity<Object> executeTwoPhaseOn(Connection conn,
                                                     SqlCompiler.CompiledQuery compiled,
                                                     MapSqlParameterSource params,
                                                     MutationExecutor mutationExecutor) throws JsonProcessingException {
        // Wrap the active connection so the existing MutationExecutor SPI works
        // inside our manual transaction. suppressClose=true keeps lifecycle ours.
        SingleConnectionDataSource scds = new SingleConnectionDataSource(conn, true);
        NamedParameterJdbcTemplate connBoundJdbc = new NamedParameterJdbcTemplate(scds);
        String json = mutationExecutor.execute(compiled, params, connBoundJdbc);
        return wrapTwoPhaseResult(json);
    }

    private ResponseEntity<Object> executeProcedureCallOn(Connection conn,
                                                          SqlCompiler.CompiledQuery compiled) throws SQLException, JsonProcessingException {
        SqlCompiler.ProcedureCallInfo callInfo = compiled.procedureCallInfo();
        String fieldName = compiled.mutationFieldName();
        String callSql = buildCallSql(callInfo);
        try (CallableStatement cs = conn.prepareCall(callSql)) { // NOSONAR — qualifiedName validated in buildCallSql, args use setObject placeholders
            var params = callInfo.allParams();
            bindInParams(cs, params);
            registerOutParams(cs, params);
            cs.execute();
            Map<String, Object> result = collectOutParams(cs, params);
            return wrapProcedureResult(fieldName, result);
        }
    }

    // ------------------------------------------------------------------------
    // Session-var + role-switch helpers.
    // ------------------------------------------------------------------------

    private static void applyRequestContext(Connection conn, JwtClaims claims) throws SQLException {
        if (claims == null) {
            return;
        }
        if (claims.userId() != null && !claims.userId().isBlank()) {
            setLocalConfig(conn, "request.user_id", claims.userId());
        }
        if (claims.projectId() != null) {
            setLocalConfig(conn, "request.project_id", claims.projectId());
        }
        if (claims.role() != null) {
            setLocalConfig(conn, "request.role", claims.role());
        }
    }

    private static void setLocalConfig(Connection conn, String key, String value) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement("SELECT set_config(?, ?, true)")) {
            pstmt.setString(1, key);
            pstmt.setString(2, value);
            pstmt.execute();
        }
    }

    /**
     * Issues {@code SET LOCAL ROLE "<role>"}. The role string MUST already be
     * validated by {@link PostgresRoleResolver} (matches {@link PostgresRoleResolver#SAFE_ROLE_NAME})
     * — Postgres does not accept parameter placeholders for {@code SET ROLE}.
     */
    private static void applySetLocalRole(Connection conn, String role) throws SQLException {
        if (!PostgresRoleResolver.SAFE_ROLE_NAME.matcher(role).matches()) {
            // Defense in depth — should already have been rejected upstream.
            throw new IllegalStateException("Role identifier rejected at execution layer: " + role);
        }
        try (PreparedStatement pstmt = conn.prepareStatement("SET LOCAL ROLE \"" + role + "\"")) { // NOSONAR — role validated against SAFE_ROLE_NAME above
            pstmt.execute();
        }
    }

    // ------------------------------------------------------------------------
    // Procedure-call helpers (unchanged from original implementation).
    // ------------------------------------------------------------------------

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

    private ResponseEntity<Object> wrapTwoPhaseResult(String json) throws JsonProcessingException {
        if (json != null) {
            Object result = objectMapper.readValue(json, Object.class);
            return ResponseEntity.ok(Map.of("data", result));
        }
        return ResponseEntity.ok(Map.of("data", Map.of()));
    }
}
