package io.github.excalibase.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.compiler.SqlCompiler;
import io.github.excalibase.compiler.WriteRetry;
import io.github.excalibase.errors.DataError;
import io.github.excalibase.errors.DataErrorException;
import io.github.excalibase.errors.DataErrors;
import io.github.excalibase.errors.ResultTooLarge;
import io.github.excalibase.config.datasource.CutResultAwareJdbcTemplate;
import io.github.excalibase.spi.MutationExecutor;
import io.github.excalibase.utils.SqlUtils;
import io.github.excalibase.http.Reply;
import io.micronaut.http.HttpResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *   <li>{@link #executeInContext} — one connection + transaction, so a write and its
 *       read-back (MySQL's two-phase mutations) are atomic and see each other.</li>
 *   <li>{@link #executeQuery} — a single read for a request without a caller. No transaction.</li>
 * </ul>
 */
@Singleton
public class QueryExecutionService {

    private static final Logger log = LoggerFactory.getLogger(QueryExecutionService.class);

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

    public HttpResponse<Object> executeQuery(SqlCompiler.CompiledQuery compiled,
                                               MapSqlParameterSource params) throws JsonProcessingException {
        String json = namedJdbc.queryForObject(compiled.sql(), params, String.class);
        return wrapResult(json);
    }

    /**
     * Runs a compiled query inside one connection + manual transaction so the
     * two-phase mutation / plain branches share atomicity.
     */
    public HttpResponse<Object> executeInContext(SqlCompiler.CompiledQuery compiled,
                                                   MapSqlParameterSource params,
                                                   MutationExecutor mutationExecutor) throws SQLException, JsonProcessingException {
        return executeInContext(compiled, params, mutationExecutor, new WriteRetry.Run());
    }

    private HttpResponse<Object> executeInContext(SqlCompiler.CompiledQuery compiled, MapSqlParameterSource params,
                                                    MutationExecutor mutationExecutor, WriteRetry.Run run)
            throws SQLException, JsonProcessingException {
        try {
            return executeOnce(compiled, params, mutationExecutor);
        } catch (SQLException | JsonProcessingException | RuntimeException e) {
            if (!run.retry(e)) throw e;
        }
        return executeInContext(compiled, params, mutationExecutor, run);
    }

    /** One transaction; a database that lost a lock to a concurrent request rolled it back, so it can run again. */
    private HttpResponse<Object> executeOnce(SqlCompiler.CompiledQuery compiled, MapSqlParameterSource params,
                                             MutationExecutor mutationExecutor)
            throws SQLException, JsonProcessingException {
        try (Connection conn = dataSource.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);
                HttpResponse<Object> result = dispatchInsideTx(conn, compiled, params, mutationExecutor);
                commit(conn);
                return result;
            } catch (SQLException | JsonProcessingException | RuntimeException e) {
                rollbackQuietly(conn, e);
                throw e;
            } finally {
                restoreAutoCommit(conn, autoCommit);
            }
        }
    }

    /**
     * Runs a mutation of ordered statements (a nested insert) in one connection and transaction, whatever
     * the request's path: a failure anywhere rolls back every statement.
     */
    public HttpResponse<Object> executeSequence(SqlCompiler.CompiledQuery compiled)
            throws SQLException, JsonProcessingException {
        try (Connection conn = dataSource.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);
                String json = new MutationSequenceRunner(conn, compiled.params()).run(compiled.sequence());
                commit(conn);
                return wrapResult(json);
            } catch (SQLException | JsonProcessingException | RuntimeException e) {
                rollbackQuietly(conn, e);
                throw e;
            } finally {
                restoreAutoCommit(conn, autoCommit);
            }
        }
    }

    /** A connection lost at commit leaves the write's outcome unknown, which the client must be told apart from a refusal. */
    private static void commit(Connection conn) throws SQLException {
        try {
            conn.commit();
        } catch (SQLException e) {
            if (DataErrors.describe(e, null).filter(error -> DataError.PROJECT_UNAVAILABLE.equals(error.code())
                    || DataError.COMMIT_UNKNOWN.equals(error.code())).isPresent()) {
                throw new DataErrorException(DataError.commitUnknown(), e);
            }
            throw e;
        }
    }

    /** On a connection that was lost this fails too, and must not replace the failure that lost it. */
    private static void restoreAutoCommit(Connection conn, boolean autoCommit) {
        try {
            conn.setAutoCommit(autoCommit);
        } catch (SQLException e) {
            log.debug("autocommit not restored: {}", e.getMessage());
        }
    }

    /** The rollback of a dead connection must not hide the failure that made it necessary. */
    private static void rollbackQuietly(Connection conn, Exception failure) {
        try {
            conn.rollback();
        } catch (SQLException e) {
            failure.addSuppressed(e);
        }
    }

    private HttpResponse<Object> dispatchInsideTx(Connection conn,
                                                    SqlCompiler.CompiledQuery compiled,
                                                    MapSqlParameterSource params,
                                                    MutationExecutor mutationExecutor) throws SQLException, JsonProcessingException {
        if (compiled.isTwoPhase()) {
            return executeTwoPhaseOn(conn, compiled, params, mutationExecutor);
        }
        return executePlainOn(conn, compiled);
    }

    // ------------------------------------------------------------------------
    // Per-branch implementations against a shared Connection.
    // ------------------------------------------------------------------------

    private HttpResponse<Object> executePlainOn(Connection conn,
                                                  SqlCompiler.CompiledQuery compiled) throws SQLException, JsonProcessingException {
        SqlUtils.ResolvedSql resolvedSql = SqlUtils.resolveNamedParams(compiled.sql(), compiled.params());
        String json;
        try (PreparedStatement pstmt = conn.prepareStatement(resolvedSql.sql())) {
            for (int i = 0; i < resolvedSql.values().size(); i++) {
                SqlUtils.bind(pstmt, i + 1, resolvedSql.values().get(i));
            }
            try (var rs = pstmt.executeQuery()) {
                json = rs.next() ? rs.getString(1) : null;
                ResultTooLarge.refuseIfCut(pstmt.getWarnings());
            }
        }
        return wrapResult(json);
    }

    private HttpResponse<Object> executeTwoPhaseOn(Connection conn,
                                                     SqlCompiler.CompiledQuery compiled,
                                                     MapSqlParameterSource params,
                                                     MutationExecutor mutationExecutor) throws JsonProcessingException {
        // Wrap the active connection so the existing MutationExecutor SPI works
        // inside our manual transaction. suppressClose=true keeps lifecycle ours.
        SingleConnectionDataSource scds = new SingleConnectionDataSource(conn, true);
        NamedParameterJdbcTemplate connBoundJdbc = new NamedParameterJdbcTemplate(new CutResultAwareJdbcTemplate(scds));
        String json = mutationExecutor.execute(compiled, params, connBoundJdbc);
        return wrapResult(json);
    }

    /**
     * The data is kept as a JSON tree so a null field survives serialization: a mutation whose
     * written row the role may not read answers {@code "field": null}, not a missing key.
     */
    private HttpResponse<Object> wrapResult(String json) throws JsonProcessingException {
        if (json != null) {
            return Reply.ok(Map.of("data", objectMapper.readTree(json)));
        }
        return Reply.ok(Map.of("data", Map.of()));
    }
}
