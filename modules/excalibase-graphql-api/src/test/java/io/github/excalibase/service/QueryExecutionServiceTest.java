package io.github.excalibase.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.compiler.SqlCompiler.CompiledQuery;
import io.github.excalibase.compiler.TwoPhaseWrite;
import io.github.excalibase.errors.DataErrorException;
import io.github.excalibase.spi.MutationExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import io.micronaut.http.HttpResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryExecutionServiceTest {

    @Mock NamedParameterJdbcTemplate namedJdbc;
    @Mock DataSource dataSource;

    private QueryExecutionService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new QueryExecutionService(namedJdbc, dataSource, objectMapper);
    }

    private CompiledQuery selectQuery(String sql) {
        return new CompiledQuery(sql, Map.of());
    }

    @Test
    @DisplayName("executeQuery wraps the JSON result under the 'data' key")
    void executeQuery_wrapsJsonInData() throws Exception {
        when(namedJdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(String.class)))
                .thenReturn("{\"users\":[{\"id\":1}]}");

        HttpResponse<Object> response = service.executeQuery(selectQuery("SELECT ..."), new MapSqlParameterSource());

        assertThat(response.getStatus().getCode() / 100 == 2).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.body();
        assertThat(body).containsKey("data");
    }

    @Test
    @DisplayName("executeQuery returns empty data map when JDBC returns null")
    void executeQuery_nullResult_returnsEmptyDataMap() throws Exception {
        when(namedJdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(String.class)))
                .thenReturn(null);

        HttpResponse<Object> response = service.executeQuery(selectQuery("SELECT ..."), new MapSqlParameterSource());

        assertThat(response.body()).isEqualTo(Map.of("data", Map.of()));
    }

    @Test
    @DisplayName("a two-phase write runs every statement on one connection and commits once")
    void twoPhaseWrite_runsOnOneConnectionInOneTransaction() throws Exception {
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        MutationExecutor exec = (c, p, jdbc) -> {
            jdbc.getJdbcTemplate().execute("INSERT INTO users (name) VALUES ('a')");
            jdbc.getJdbcTemplate().execute("SELECT 1");
            return "{\"createUser\":{\"id\":7}}";
        };

        HttpResponse<Object> response = service.executeInContext(twoPhase(), new MapSqlParameterSource(), exec);

        verify(dataSource, times(1)).getConnection();
        verify(connection, times(2)).createStatement();
        verify(connection).setAutoCommit(false);
        verify(connection).commit();
        verify(connection, never()).rollback();
        assertThat(response.body()).isEqualTo(Map.of("data", new ObjectMapper().readTree("{\"createUser\":{\"id\":7}}")));
    }

    @Test
    @DisplayName("a two-phase write that fails rolls back everything it wrote")
    void twoPhaseWrite_failure_rollsBack() throws Exception {
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        MutationExecutor exec = (c, p, jdbc) -> {
            throw new IllegalArgumentException("duplicate key");
        };

        CompiledQuery write = twoPhase();
        MapSqlParameterSource params = new MapSqlParameterSource();

        assertThatThrownBy(() -> service.executeInContext(write, params, exec))
                .isInstanceOf(IllegalArgumentException.class);

        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(connection).setAutoCommit(true);
    }

    @Test
    @DisplayName("a connection lost while committing is commit_unknown, not an outage a client may retry")
    void commitLostConnection_isCommitUnknown_evenWhenTheRollbackFailsToo() throws Exception {
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        doThrow(new java.sql.SQLException("connection closed", "08006")).when(connection).commit();
        doThrow(new java.sql.SQLException("connection closed", "08003")).when(connection).rollback();
        MutationExecutor exec = (c, p, jdbc) -> "{\"createUser\":{\"id\":7}}";

        CompiledQuery write = twoPhase();
        MapSqlParameterSource params = new MapSqlParameterSource();

        assertThatThrownBy(() -> service.executeInContext(write, params, exec))
                .isInstanceOfSatisfying(io.github.excalibase.errors.DataErrorException.class, e -> {
                    assertThat(e.error().code()).isEqualTo("commit_unknown");
                    assertThat(e.error().message()).doesNotContain("connection closed");
                });
    }

    private static final SQLWarning CUT = new SQLWarning("Result of json_arrayagg() was larger than max_allowed_packet",
            "HY000", 1301);

    private PreparedStatement preparedReturning(String json, SQLWarning warning) throws Exception {
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        ResultSet rows = mock(ResultSet.class);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getString(1)).thenReturn(json);
        when(statement.getWarnings()).thenReturn(warning);
        return statement;
    }

    @Test
    @DisplayName("a signed-in read the database cut short is result_too_large, not empty data")
    void callerRead_cutShort_isRefused() throws Exception {
        preparedReturning(null, CUT);
        CompiledQuery read = selectQuery("SELECT 1");
        MapSqlParameterSource none = new MapSqlParameterSource();
        MutationExecutor unused = (c, p, jdbc) -> null;

        assertThatThrownBy(() -> service.executeInContext(read, none, unused))
                .isInstanceOfSatisfying(DataErrorException.class,
                        refused -> assertThat(refused.error().code()).isEqualTo("result_too_large"));
    }

    @Test
    @DisplayName("a write that cut its own answer short is refused and rolled back too")
    void twoPhaseWrite_cutShort_isRefused() throws Exception {
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.getWarnings()).thenReturn(CUT);
        MutationExecutor exec = (c, p, jdbc) -> {
            jdbc.getJdbcTemplate().execute("SELECT 1");
            return "{\"createUser\":{\"id\":7}}";
        };
        CompiledQuery write = twoPhase();
        MapSqlParameterSource none = new MapSqlParameterSource();

        assertThatThrownBy(() -> service.executeInContext(write, none, exec))
                .isInstanceOfSatisfying(DataErrorException.class,
                        refused -> assertThat(refused.error().code()).isEqualTo("result_too_large"));
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    @DisplayName("an answer with no warning and no value is still no data: only the warning is a refusal")
    void callerRead_noValueNoWarning_isEmptyData() throws Exception {
        preparedReturning(null, null);

        HttpResponse<Object> response = service.executeInContext(selectQuery("SELECT 1"), new MapSqlParameterSource(),
                (c, p, jdbc) -> null);

        assertThat(response.body()).isEqualTo(Map.of("data", Map.of()));
    }

    private static CompiledQuery twoPhase() {
        return CompiledQuery.twoPhase(new TwoPhaseWrite() { }, Map.of());
    }
}
