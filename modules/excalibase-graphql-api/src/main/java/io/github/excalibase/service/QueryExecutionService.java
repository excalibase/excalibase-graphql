package io.github.excalibase.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.compiler.SqlCompiler;
import io.github.excalibase.spi.MutationExecutor;
import io.github.excalibase.utils.SqlUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;

/**
 * Executes compiled SQL against the database. Row/column security is already
 * baked into the compiled SQL by the engine, so this layer only runs it.
 *
 * <ul>
 *   <li>{@link #executeInContext} — Postgres path: one connection + transaction
 *       so two-phase mutation / plain branches share atomicity.</li>
 *   <li>{@link #executeQuery} / {@link #executeTwoPhase} —
 *       MySQL / Mongo or single-tenant Postgres with no JWT. No transaction.</li>
 * </ul>
 */
@Service
public class QueryExecutionService {

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

    // ------------------------------------------------------------------------
    // Legacy paths — used for MySQL / Mongo or single-tenant Postgres, no JWT.
    // ------------------------------------------------------------------------

    public ResponseEntity<Object> executeQuery(SqlCompiler.CompiledQuery compiled,
                                               MapSqlParameterSource params) throws JsonProcessingException {
        String json = namedJdbc.queryForObject(compiled.sql(), params, String.class);
        return wrapResult(json);
    }

    public ResponseEntity<Object> executeTwoPhase(SqlCompiler.CompiledQuery compiled,
                                                  MapSqlParameterSource params,
                                                  MutationExecutor mutationExecutor) throws JsonProcessingException {
        String json = mutationExecutor.execute(compiled, params, namedJdbc);
        return wrapTwoPhaseResult(json);
    }

    /**
     * Runs a compiled query inside one connection + manual transaction so the
     * two-phase mutation / plain branches share atomicity.
     */
    public ResponseEntity<Object> executeInContext(SqlCompiler.CompiledQuery compiled,
                                                   MapSqlParameterSource params,
                                                   MutationExecutor mutationExecutor) throws SQLException, JsonProcessingException {
        try (Connection conn = dataSource.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);
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

    /**
     * Runs a mutation of ordered statements (a nested insert) in one connection and transaction, whatever
     * the request's path: a failure anywhere rolls back every statement.
     */
    public ResponseEntity<Object> executeSequence(SqlCompiler.CompiledQuery compiled)
            throws SQLException, JsonProcessingException {
        try (Connection conn = dataSource.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);
                String json = new MutationSequenceRunner(conn, compiled.params()).run(compiled.sequence());
                conn.commit();
                return wrapResult(json);
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
        return wrapResult(json);
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

    /**
     * The data is kept as a JSON tree so a null field survives serialization: a mutation whose
     * written row the role may not read answers {@code "field": null}, not a missing key.
     */
    private ResponseEntity<Object> wrapResult(String json) throws JsonProcessingException {
        if (json != null) {
            return ResponseEntity.ok(Map.of("data", objectMapper.readTree(json)));
        }
        return ResponseEntity.ok(Map.of("data", Map.of()));
    }

    private ResponseEntity<Object> wrapTwoPhaseResult(String json) throws JsonProcessingException {
        if (json != null) {
            return ResponseEntity.ok(Map.of("data", objectMapper.readTree(json)));
        }
        return ResponseEntity.ok(Map.of("data", Map.of()));
    }
}
